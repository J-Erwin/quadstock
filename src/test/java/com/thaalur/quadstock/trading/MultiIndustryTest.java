package com.thaalur.quadstock.trading;

import static com.thaalur.quadstock.trading.HoldingsLedger.*;
import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class MultiIndustryTest {
  private final UUID player = UUID.randomUUID();
  private final TradingDesk.Gateway gateway =
      new TradingDesk.Gateway() {
        public String identity() {
          return "Test";
        }

        public int digits() {
          return 2;
        }

        public BigDecimal balance(UUID p) {
          return new BigDecimal("100000");
        }

        public boolean pay(UUID p, Side s, BigDecimal a) {
          return true;
        }
      };

  private TradingDesk desk(HoldingsLedger l, String ticker, String market, String cap) {
    var d =
        new TradingDesk(
            l,
            gateway,
            new TradingPolicy(
                TradePreview.SettlementMode.RESERVE_BACKED, "Test", 2, new BigDecimal("10000")),
            ticker,
            market);
    d.configure(ticker, market, new BigDecimal(cap), 1000, 10000, true);
    return d;
  }

  private void trade(TradingDesk d, Side side, int qty, double price, long tick) throws Exception {
    var q = d.quote(player, side, qty, price, tick);
    d.confirm(player, q.id(), tick);
  }

  private void amount(String n, BigDecimal actual) {
    assertEquals(0, new BigDecimal(n).compareTo(actual));
  }

  @Test
  public void holdingsAreSeparateReservesSharedAndOtherMarketsIsolated() throws Exception {
    Path dir = Files.createTempDirectory("multi-");
    try (var l = HoldingsLedger.open(dir)) {
      var a = desk(l, "AGRI", "OVERWORLD", "10000");
      var m = desk(l, "MINE", "OVERWORLD", "500");
      var f = desk(l, "FISH", "OCEAN", "10000");
      trade(a, Side.BUY, 10, 100, 0);
      trade(m, Side.BUY, 1, 100, 1);
      trade(m, Side.SELL, 1, 500, 2);
      amount("600", l.reserve("OVERWORLD"));
      assertEquals(10, a.position(player).shares());
      assertEquals(0, m.position(player).shares());
      amount("400", m.position(player).realized());
      trade(m, Side.BUY, 1, 100, 3);
      assertThrows(IllegalArgumentException.class, () -> m.quote(player, Side.SELL, 1, 100, 4));
      trade(f, Side.BUY, 1, 100, 4);
      assertThrows(IllegalArgumentException.class, () -> f.quote(player, Side.SELL, 1, 200, 5));
      amount("100", l.reserve("OCEAN"));
    }
    try (var l = HoldingsLedger.open(dir)) {
      assertEquals(10, l.position(player, "AGRI").shares());
      assertEquals(1, l.position(player, "MINE").shares());
      amount("500", l.paidOut(0, "MINE"));
      amount("0", l.paidOut(0, "AGRI"));
    }
  }

  @Test
  public void configuredPauseExpiryAndOrderLimitPreventPayment() throws Exception {
    try (var l = HoldingsLedger.open(Files.createTempDirectory("multi-settings-"))) {
      var d = desk(l, "AGRI", "OVERWORLD", "1000");
      d.configure("AGRI", "OVERWORLD", null, 50, 5, false);
      assertThrows(IllegalStateException.class, () -> d.quote(player, Side.BUY, 1, 100, 0));
      d.configure("AGRI", "OVERWORLD", null, 50, 5, true);
      assertThrows(IllegalArgumentException.class, () -> d.quote(player, Side.BUY, 6, 100, 0));
      var q = d.quote(player, Side.BUY, 1, 100, 0);
      assertThrows(IllegalStateException.class, () -> d.confirm(player, q.id(), 50));
      assertEquals(0, l.count());
    }
  }
}
