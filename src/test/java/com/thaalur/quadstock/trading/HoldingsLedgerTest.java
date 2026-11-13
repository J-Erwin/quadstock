package com.thaalur.quadstock.trading;

import static com.thaalur.quadstock.trading.HoldingsLedger.*;
import static com.thaalur.quadstock.trading.TradePreview.SettlementMode.*;
import static org.junit.Assert.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class HoldingsLedgerTest {
  private final UUID player = UUID.randomUUID();

  private Intent intent(Side side, int shares, String amount, long tick) {
    return new Intent(
        UUID.randomUUID(),
        player,
        side,
        shares,
        new BigDecimal(amount),
        UNLIMITED_LIQUIDITY,
        "Fake provider",
        tick,
        "AGRI",
        "OVERWORLD");
  }

  private void complete(HoldingsLedger l, Intent i) throws Exception {
    l.prepare(i);
    assertTrue(l.markAttempt(i.id(), i.tick() + 1));
    l.paymentResult(i.id(), true, i.tick() + 2);
    l.commit(i.id(), i.tick() + 3);
  }

  @Test
  public void newAccountStartsEmpty() throws Exception {
    try (var l = HoldingsLedger.open(Files.createTempDirectory("ledger-empty"))) {
      assertEquals(Position.empty(), l.position(player, "AGRI"));
      assertTrue(l.history(player).isEmpty());
      assertFalse(l.blocked());
    }
  }

  @Test
  public void buysPartialSalesAndFullExitReconstructCostAndProfit() throws Exception {
    Path dir = Files.createTempDirectory("ledger-cost");
    Position expected;
    try (var l = HoldingsLedger.open(dir)) {
      complete(l, intent(Side.BUY, 3, "10.00", 0));
      complete(l, intent(Side.BUY, 2, "10.00", 10));
      complete(l, intent(Side.SELL, 2, "12.00", 20));
      assertEquals(3, l.position(player, "AGRI").shares());
      assertEquals(0, new BigDecimal("12").compareTo(l.position(player, "AGRI").cost()));
      assertEquals(0, new BigDecimal("4").compareTo(l.position(player, "AGRI").realized()));
      complete(l, intent(Side.SELL, 3, "9.00", 30));
      expected = l.position(player, "AGRI");
      assertEquals(0, expected.shares());
      assertEquals(0, expected.cost().signum());
      assertEquals(0, new BigDecimal("1").compareTo(expected.realized()));
    }
    try (var resumed = HoldingsLedger.open(dir)) {
      assertEquals(expected, resumed.position(player, "AGRI"));
      assertEquals(4, resumed.history(player).size());
    }
  }

  @Test
  public void duplicateOperationsCannotRepeatProviderAttemptOrCreditHoldings() throws Exception {
    try (var l = HoldingsLedger.open(Files.createTempDirectory("ledger-idem"))) {
      var i = intent(Side.BUY, 10, "100.00", 0);
      l.prepare(i);
      l.prepare(i);
      assertEquals(1, l.count());
      assertTrue(l.markAttempt(i.id(), 1));
      assertFalse(l.markAttempt(i.id(), 2));
      assertThrows(IllegalStateException.class, () -> l.commit(i.id(), 3));
      l.paymentResult(i.id(), true, 4);
      l.paymentResult(i.id(), true, 5);
      l.commit(i.id(), 6);
      l.commit(i.id(), 7);
      assertEquals(10, l.position(player, "AGRI").shares());
      assertEquals(4, l.history(player).getFirst().steps().size());
      var changed =
          new Intent(
              i.id(),
              i.player(),
              Side.BUY,
              11,
              i.amount(),
              i.mode(),
              i.provider(),
              i.tick(),
              "AGRI",
              "OVERWORLD");
      assertThrows(IllegalArgumentException.class, () -> l.prepare(changed));
      assertThrows(IllegalStateException.class, () -> l.paymentResult(i.id(), false, 8));
    }
  }

  @Test
  public void crashDuringProviderCallRemainsBlockedAndNeverAutomaticallyRetries() throws Exception {
    Path dir = Files.createTempDirectory("ledger-uncertain");
    var i = intent(Side.BUY, 1, "100.00", 0);
    try (var l = HoldingsLedger.open(dir)) {
      l.prepare(i);
      l.markAttempt(i.id(), 1);
    }
    try (var l = HoldingsLedger.open(dir)) {
      assertTrue(l.blocked());
      assertEquals(State.IN_FLIGHT, l.pending().orElseThrow().state());
      assertFalse(l.markAttempt(i.id(), 2));
      assertEquals(0, l.position(player, "AGRI").shares());
      assertThrows(IllegalStateException.class, () -> l.prepare(intent(Side.BUY, 1, "100.00", 3)));
      assertThrows(IllegalStateException.class, () -> l.cancelPrepared(i.id(), 3));
    }
  }

  @Test
  public void confirmedPaymentCanCommitAfterRestartWithoutCallingProviderAgain() throws Exception {
    Path dir = Files.createTempDirectory("ledger-paid");
    var i = intent(Side.BUY, 1, "100.00", 0);
    try (var l = HoldingsLedger.open(dir)) {
      l.prepare(i);
      l.markAttempt(i.id(), 1);
      l.paymentResult(i.id(), true, 2);
    }
    try (var l = HoldingsLedger.open(dir)) {
      assertFalse(l.markAttempt(i.id(), 3));
      l.commit(i.id(), 3);
      assertEquals(1, l.position(player, "AGRI").shares());
      assertFalse(l.blocked());
    }
  }

  @Test
  public void cancellationAndConfirmedFailureDoNotChangeHoldings() throws Exception {
    try (var l = HoldingsLedger.open(Files.createTempDirectory("ledger-fail"))) {
      var cancelled = intent(Side.BUY, 1, "100.00", 0);
      l.prepare(cancelled);
      l.cancelPrepared(cancelled.id(), 1);
      var failed = intent(Side.BUY, 1, "100.00", 2);
      l.prepare(failed);
      l.markAttempt(failed.id(), 3);
      l.paymentResult(failed.id(), false, 4);
      assertFalse(l.blocked());
      assertEquals(Position.empty(), l.position(player, "AGRI"));
      assertThrows(
          IllegalArgumentException.class, () -> l.prepare(intent(Side.SELL, 1, "100.00", 5)));
    }
  }

  @Test
  public void fractionalCostRemainderIsConsumedOnFinalSale() throws Exception {
    try (var l = HoldingsLedger.open(Files.createTempDirectory("ledger-rounding"))) {
      complete(l, intent(Side.BUY, 3, "1.00", 0));
      for (int n = 0; n < 3; n++) complete(l, intent(Side.SELL, 1, "0.50", 10 + n * 10));
      assertEquals(0, l.position(player, "AGRI").cost().signum());
      assertEquals(0, new BigDecimal("0.50").compareTo(l.position(player, "AGRI").realized()));
    }
  }

  @Test
  public void exclusiveLockAndCorruptionFailWithoutReset() throws Exception {
    Path dir = Files.createTempDirectory("ledger-corrupt");
    try (var l = HoldingsLedger.open(dir)) {
      assertThrows(IOException.class, () -> HoldingsLedger.open(dir));
      complete(l, intent(Side.BUY, 1, "10.00", 0));
    }
    Path file = dir.resolve("quadstock.db");
    byte[] data = "not a SQLite database".getBytes();
    Files.write(file, data);
    assertThrows(IOException.class, () -> HoldingsLedger.open(dir));
    assertArrayEquals(data, Files.readAllBytes(file));
  }

  @Test
  public void failedPersistenceDoesNotGrantHoldingsAndPoisonsWriter() throws Exception {
    Path dir = Files.createTempDirectory("ledger-writefail");
    try (var l = HoldingsLedger.open(dir)) {
      var i = intent(Side.BUY, 1, "100.00", 0);
      l.prepare(i);
      l.markAttempt(i.id(), 1);
      l.paymentResult(i.id(), true, 2);
      try (var connection = com.thaalur.quadstock.persistence.Database.open(dir);
          var sql = connection.createStatement()) {
        sql.execute(
            "CREATE TRIGGER fail_commit BEFORE INSERT ON trade_steps WHEN NEW.state='COMMITTED'"
                + " BEGIN SELECT RAISE(ABORT,'injected disk failure'); END");
      }
      assertThrows(IOException.class, () -> l.commit(i.id(), 3));
      assertEquals(0, l.position(player, "AGRI").shares());
      assertTrue(l.blocked());
      assertThrows(IllegalStateException.class, () -> l.commit(i.id(), 4));
    }
  }

  @Test
  public void modeIsRecordedWithoutPretendingReserveSettlementExists() throws Exception {
    try (var l = HoldingsLedger.open(Files.createTempDirectory("ledger-mode"))) {
      var i = intent(Side.BUY, 1, "100.00", 0);
      var reserve =
          new Intent(
              i.id(),
              i.player(),
              i.side(),
              i.shares(),
              i.amount(),
              RESERVE_BACKED,
              i.provider(),
              i.tick(),
              "AGRI",
              "OVERWORLD");
      complete(l, reserve);
      assertEquals(RESERVE_BACKED, l.history(player).getFirst().intent().mode());
    }
  }
}
