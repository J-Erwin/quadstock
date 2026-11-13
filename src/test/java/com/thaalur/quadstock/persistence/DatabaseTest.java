package com.thaalur.quadstock.persistence;

import static org.junit.Assert.*;

import com.thaalur.quadstock.data.LivingMarket;
import com.thaalur.quadstock.data.MarketCalendar;
import com.thaalur.quadstock.notifications.Subscriptions;
import com.thaalur.quadstock.trading.HoldingsLedger;
import com.thaalur.quadstock.trading.TradePreview.SettlementMode;
import com.thaalur.quadstock.trading.TradingPolicy;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Real SQLite tests for persistence boundaries, not mocked JDBC behavior. */
public class DatabaseTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private Path directory() {
    return temporary.getRoot().toPath();
  }

  @Test
  public void allStateUsesOneDatabaseAndRestarts() throws Exception {
    var dir = directory();
    var player = UUID.randomUUID();
    var calendar = MarketCalendar.load(dir);
    assertEquals(0, calendar.ticks());
    calendar.addTicks(24000);
    calendar.save();
    var market =
        LivingMarket.load(dir, calendar.ticks(), com.thaalur.quadstock.TestMarkets.industry(), 1);
    market.advanceTo(25000);
    calendar.addTicks(1000);
    calendar.save();
    market.save();
    var preferences = Subscriptions.load(dir);
    preferences.set(player, true);
    var policy =
        new TradingPolicy(SettlementMode.RESERVE_BACKED, "Test", 2, new BigDecimal("1000"));
    policy.install(dir);
    try (var ledger = HoldingsLedger.open(dir)) {
      var intent =
          new HoldingsLedger.Intent(
              UUID.randomUUID(),
              player,
              HoldingsLedger.Side.BUY,
              1,
              new BigDecimal("100.00"),
              policy.mode(),
              policy.provider(),
              25000,
              "AGRI",
              "OVERWORLD");
      ledger.prepare(intent);
      ledger.markAttempt(intent.id(), 25000);
      ledger.paymentResult(intent.id(), true, 25000);
      ledger.commit(intent.id(), 25000);
    }
    assertEquals(25000, MarketCalendar.load(dir).ticks());
    assertEquals(
        market.observations(),
        LivingMarket.load(dir, 25000, com.thaalur.quadstock.TestMarkets.industry(), 1)
            .observations());
    assertTrue(Subscriptions.load(dir).contains(player));
    assertEquals(policy, TradingPolicy.load(dir).orElseThrow());
    try (var ledger = HoldingsLedger.open(dir)) {
      assertEquals(1, ledger.position(player, "AGRI").shares());
    }
    try (var paths = Files.list(dir)) {
      assertTrue(paths.allMatch(p -> p.getFileName().toString().startsWith("quadstock.")));
    }
  }

  @Test
  public void unsupportedSchemaFailsWithoutReplacingIt() throws Exception {
    try (var c = Database.open(directory());
        var sql = c.createStatement()) {
      sql.execute("PRAGMA user_version=999");
    }
    assertThrows(IOException.class, () -> Database.open(directory()));
    try (var c =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + directory().resolve("quadstock.db"));
        var sql = c.createStatement();
        var rows = sql.executeQuery("PRAGMA user_version")) {
      assertTrue(rows.next());
      assertEquals(999, rows.getInt(1));
    }
  }

  @Test
  public void intentAndFirstStepRollbackTogether() throws Exception {
    try (var c = Database.open(directory());
        var sql = c.createStatement()) {
      sql.execute(
          "CREATE TRIGGER reject_step BEFORE INSERT ON trade_steps BEGIN SELECT"
              + " RAISE(ABORT,'injected failure'); END");
    }
    try (var ledger = HoldingsLedger.open(directory())) {
      var intent =
          new HoldingsLedger.Intent(
              UUID.randomUUID(),
              UUID.randomUUID(),
              HoldingsLedger.Side.BUY,
              1,
              new BigDecimal("1.00"),
              SettlementMode.UNLIMITED_LIQUIDITY,
              "Test",
              0,
              "AGRI",
              "OVERWORLD");
      assertThrows(IOException.class, () -> ledger.prepare(intent));
      assertEquals(0, ledger.count());
      assertTrue(ledger.blocked());
    }
    try (var c = Database.open(directory());
        var sql = c.createStatement();
        var rows = sql.executeQuery("SELECT COUNT(*) FROM trades")) {
      assertTrue(rows.next());
      assertEquals(0, rows.getInt(1));
    }
  }

  @Test
  public void calendarRejectsCorruptCheckpointAndTimeReversal() throws Exception {
    var calendar = MarketCalendar.load(directory());
    assertThrows(IllegalArgumentException.class, () -> calendar.addTicks(-1));
    assertEquals("Month 1 Day 1 00:00", MarketCalendar.format(0));
    assertEquals("Month 2 Day 1 00:00", MarketCalendar.format(MarketCalendar.MONTH_TICKS));
    StateStore.write(directory(), "calendar", "invalid".getBytes());
    assertThrows(IOException.class, () -> MarketCalendar.load(directory()));
    assertEquals("invalid", new String(StateStore.read(directory(), "calendar").orElseThrow()));
  }

  @Test
  public void coordinatedCheckpointRollsBackEveryValueOnFailure() throws Exception {
    StateStore.write(directory(), "calendar", new byte[] {1});
    StateStore.write(directory(), "market/AGRI", new byte[] {2});
    try (var c = Database.open(directory());
        var sql = c.createStatement()) {
      sql.execute(
          "CREATE TRIGGER reject_market BEFORE UPDATE ON state WHEN NEW.name='market/AGRI' BEGIN"
              + " SELECT RAISE(ABORT,'injected failure'); END");
    }
    var values = new java.util.LinkedHashMap<String, byte[]>();
    values.put("calendar", new byte[] {3});
    values.put("market/AGRI", new byte[] {4});
    assertThrows(IOException.class, () -> StateStore.writeAll(directory(), values));
    assertArrayEquals(new byte[] {1}, StateStore.read(directory(), "calendar").orElseThrow());
    assertArrayEquals(new byte[] {2}, StateStore.read(directory(), "market/AGRI").orElseThrow());
  }

  @Test
  public void expandedPriceRangeAndTickerIsolationSurviveRestart() throws Exception {
    var low =
        new com.thaalur.quadstock.config.MarketSettings.Industry(
            "LOW", "OVERWORLD", "Low", "STONE", java.util.Set.of("STONE"), 1, 0, false, true, null);
    var high =
        new com.thaalur.quadstock.config.MarketSettings.Industry(
            "HIGH",
            "OVERWORLD",
            "High",
            "DIAMOND",
            java.util.Set.of("DIAMOND"),
            5000,
            0,
            false,
            true,
            null);
    var lower = LivingMarket.load(directory(), 0, low, 0);
    var higher = LivingMarket.load(directory(), 0, high, 0);
    lower.advanceTo(24000);
    higher.advanceTo(24000);
    lower.save();
    higher.save();
    assertEquals(1, LivingMarket.load(directory(), 24000, low, 0).currentPrice(), 0);
    assertEquals(5000, LivingMarket.load(directory(), 24000, high, 0).currentPrice(), 0);
    assertEquals(
        lower.observations(), LivingMarket.load(directory(), 24000, low, 0).observations());
    assertEquals(
        higher.observations(), LivingMarket.load(directory(), 24000, high, 0).observations());
  }
}
