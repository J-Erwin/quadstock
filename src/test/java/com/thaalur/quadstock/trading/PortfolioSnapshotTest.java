package com.thaalur.quadstock.trading;

import static com.thaalur.quadstock.trading.HoldingsLedger.*;
import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public class PortfolioSnapshotTest {
  private PortfolioSnapshot capture(HoldingsLedger ledger, UUID player, double mark) {
    return PortfolioSnapshot.capture(
        List.of(
            new PortfolioSnapshot.Source(
                "CUSTOM", "Custom industry", "DIAMOND", ledger.position(player, "CUSTOM"), mark)));
  }

  private Intent intent(UUID player, Side side, int shares, String amount, long tick) {
    return new Intent(
        UUID.randomUUID(),
        player,
        side,
        shares,
        new BigDecimal(amount),
        TradePreview.SettlementMode.UNLIMITED_LIQUIDITY,
        "Test",
        tick,
        "CUSTOM",
        "EXCHANGE");
  }

  @Test
  public void onlyCommittedHoldingsRefreshAndSurviveRestart() throws Exception {
    var dir = Files.createTempDirectory("tab-portfolio");
    UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
    try (var ledger = HoldingsLedger.open(dir)) {
      var buy = intent(owner, Side.BUY, 3, "30", 0);
      ledger.prepare(buy);
      ledger.markAttempt(buy.id(), 1);
      assertEquals(0, ledger.holdingsRevision());
      assertTrue(capture(ledger, owner, 12).holdings().isEmpty());
      ledger.paymentResult(buy.id(), true, 2);
      assertTrue(capture(ledger, owner, 12).holdings().isEmpty());
      ledger.commit(buy.id(), 3);
      ledger.commit(buy.id(), 4);
      assertEquals(1, ledger.holdingsRevision());
      assertEquals(0, new BigDecimal("6").compareTo(capture(ledger, owner, 12).unrealized()));
      assertEquals(0, new BigDecimal("-6").compareTo(capture(ledger, owner, 8).unrealized()));
      assertTrue(capture(ledger, other, 12).holdings().isEmpty());
      var sell = intent(owner, Side.SELL, 1, "12", 5);
      ledger.prepare(sell);
      ledger.markAttempt(sell.id(), 6);
      ledger.paymentResult(sell.id(), true, 7);
      ledger.commit(sell.id(), 8);
      assertEquals(2, ledger.holdingsRevision());
      var partial = capture(ledger, owner, 9);
      assertEquals(2, partial.holdings().getFirst().shares());
      assertEquals(0, new BigDecimal("20").compareTo(partial.cost()));
      assertEquals(0, new BigDecimal("-2").compareTo(partial.unrealized()));
      assertEquals(0, new BigDecimal("2").compareTo(partial.realized()));
    }
    try (var ledger = HoldingsLedger.open(dir)) {
      assertEquals(2, capture(ledger, owner, 9).holdings().getFirst().shares());
      var sell = intent(owner, Side.SELL, 2, "18", 9);
      ledger.prepare(sell);
      ledger.markAttempt(sell.id(), 10);
      ledger.paymentResult(sell.id(), true, 11);
      ledger.commit(sell.id(), 12);
      assertTrue(capture(ledger, owner, 9).holdings().isEmpty());
      assertEquals(0, capture(ledger, owner, 9).value().signum());
    }
  }

  @Test
  public void totalIncludesAllIndustriesAndClosedRealizedProfit() {
    var snapshot =
        PortfolioSnapshot.capture(
            List.of(
                new PortfolioSnapshot.Source(
                    "X",
                    "One",
                    "WHEAT",
                    new Position(3, new BigDecimal("0.30"), BigDecimal.ZERO),
                    .1),
                new PortfolioSnapshot.Source(
                    "Y", "Two", "STONE", new Position(2, new BigDecimal("4"), BigDecimal.ZERO), 3),
                new PortfolioSnapshot.Source(
                    "Z",
                    "Closed",
                    "CHEST",
                    new Position(0, BigDecimal.ZERO, new BigDecimal("7")),
                    100)));
    assertEquals(2, snapshot.holdings().size());
    assertEquals(0, new BigDecimal("6.30").compareTo(snapshot.value()));
    assertEquals(0, new BigDecimal("2").compareTo(snapshot.unrealized()));
    assertEquals(0, new BigDecimal("7").compareTo(snapshot.realized()));
  }
}
