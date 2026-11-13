package com.thaalur.quadstock.trading;

import static com.thaalur.quadstock.trading.HoldingsLedger.*;
import static org.junit.Assert.*;

import com.thaalur.quadstock.data.LivingMarket.PriceObservation;
import java.math.BigDecimal;
import java.util.*;
import org.junit.Test;

public class PortfolioHistoryTest {
  private Entry trade(Side side, int qty, String amount, long intentTick, long commitTick) {
    return new Entry(
        new Intent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            side,
            qty,
            new BigDecimal(amount),
            TradePreview.SettlementMode.RESERVE_BACKED,
            "Test",
            intentTick,
            "AGRI",
            "OVERWORLD"),
        List.of(
            new Step(State.PREPARED, intentTick),
            new Step(State.IN_FLIGHT, intentTick),
            new Step(State.PAID, intentTick),
            new Step(State.COMMITTED, commitTick)));
  }

  private List<PortfolioHistory.Point> points(
      List<Entry> trades, List<PriceObservation> observations, long now, long window) {
    return PortfolioHistory.bins(trades, observations, now, window, 9).stream()
        .flatMap(b -> b.points().stream())
        .toList();
  }

  private void amount(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual));
  }

  @Test
  public void additionalInvestmentDoesNotAppearAsProfit() {
    var p =
        points(
            List.of(trade(Side.BUY, 10, "1000", 0, 0), trade(Side.BUY, 10, "1000", 10, 10)),
            List.of(new PriceObservation(0, 100), new PriceObservation(10, 100)),
            10,
            100);
    amount("1000", p.getFirst().value());
    amount("2000", p.getLast().value());
    amount("0", p.getFirst().profit());
    amount("0", p.getLast().profit());
  }

  @Test
  public void saleAndFullExitPreserveProfitWithoutHoldings() {
    var p =
        points(
            List.of(
                trade(Side.BUY, 10, "1000", 0, 0),
                trade(Side.SELL, 5, "600", 10, 10),
                trade(Side.SELL, 5, "600", 20, 20)),
            List.of(
                new PriceObservation(0, 100),
                new PriceObservation(10, 120),
                new PriceObservation(20, 120),
                new PriceObservation(30, 150)),
            30,
            100);
    amount("200", p.get(1).profit());
    amount("200", p.getLast().profit());
    amount("0", p.getLast().value());
    assertEquals(0, p.getLast().shares());
  }

  @Test
  public void historicalHoldingsUseCommitTimeAndIgnorePending() {
    var buy = trade(Side.BUY, 10, "1000", 0, 10);
    var pending = new Entry(buy.intent(), buy.steps().subList(0, 3));
    assertTrue(points(List.of(pending), List.of(new PriceObservation(20, 100)), 20, 20).isEmpty());
    var p =
        points(
            List.of(buy),
            List.of(new PriceObservation(5, 100), new PriceObservation(10, 100)),
            10,
            100);
    assertEquals(1, p.size());
    assertEquals(10, p.getFirst().tick());
  }

  @Test
  public void retainedWindowReplaysOlderTradesAndDoesNotFillGaps() {
    var bins =
        PortfolioHistory.bins(
            List.of(trade(Side.BUY, 10, "1000", 0, 0)),
            List.of(new PriceObservation(95, 90), new PriceObservation(100, 110)),
            100,
            9,
            9);
    assertEquals(7, bins.stream().filter(b -> b.points().isEmpty()).count());
    var p = bins.getLast().points().getLast();
    amount("1100", p.value());
    amount("100", p.profit());
  }

  @Test
  public void emptyAccountAndFutureSamplesHaveNoInventedHistory() {
    assertTrue(points(List.of(), List.of(new PriceObservation(0, 100)), 0, 10).isEmpty());
    assertTrue(
        points(
                List.of(trade(Side.BUY, 1, "100", 0, 0)),
                List.of(new PriceObservation(20, 100)),
                10,
                10)
            .isEmpty());
  }

  @Test
  public void refreshWithinAnIntervalDoesNotRebucketAnyTimeRange() {
    var trades = List.of(trade(Side.BUY, 1, "100", 0, 0));
    for (var range : com.thaalur.quadstock.data.TimeRange.values()) {
      long boundary = range.ticks() * 2;
      var observations =
          List.of(
              new PriceObservation(boundary - range.ticks() / 2, 90),
              new PriceObservation(boundary, 110));
      assertEquals(
          range.name(),
          PortfolioHistory.bins(trades, observations, boundary + 1, range.ticks(), 9),
          PortfolioHistory.bins(trades, observations, boundary + 40, range.ticks(), 9));
    }
  }

  @Test
  public void crossingBoundaryAdvancesExactlyOneColumn() {
    var trades = List.of(trade(Side.BUY, 1, "100", 0, 0));
    var observations =
        List.of(
            new PriceObservation(100, 100),
            new PriceObservation(201, 110),
            new PriceObservation(999, 120));
    var before = PortfolioHistory.bins(trades, observations, 999, 1000, 9);
    var after = PortfolioHistory.bins(trades, observations, 1000, 1000, 9);
    for (int i = 1; i < 9; i++) assertEquals(before.get(i), after.get(i - 1));
    assertEquals(1000, after.getLast().start());
    assertTrue(after.getLast().points().isEmpty());
  }

  @Test
  public void newObservationUpdatesOnlyTheOpenInterval() {
    var trades = List.of(trade(Side.BUY, 1, "100", 0, 0));
    var observations = List.of(new PriceObservation(1900, 100), new PriceObservation(2000, 110));
    var before = PortfolioHistory.bins(trades, observations, 2001, 1000, 9);
    var extended = new ArrayList<>(observations);
    extended.add(new PriceObservation(2040, 120));
    var after = PortfolioHistory.bins(trades, extended, 2041, 1000, 9);
    for (int i = 0; i < 8; i++) assertEquals(before.get(i), after.get(i));
    assertEquals(2, after.getLast().points().size());
  }

  @Test
  public void livePortfolioUsesRollingHourWithoutChangingAccounting() {
    var trades = List.of(trade(Side.BUY, 10, "1000", 0, 0), trade(Side.SELL, 5, "600", 1500, 1500));
    var observations =
        List.of(
            new PriceObservation(999, 100),
            new PriceObservation(1000, 100),
            new PriceObservation(2000, 120),
            new PriceObservation(2100, 130));
    var bins = PortfolioHistory.bins(trades, observations, 2000, 1000, 9, true);
    var all = bins.stream().flatMap(b -> b.points().stream()).toList();
    assertEquals(2, all.size());
    assertEquals(1000, all.getFirst().tick());
    assertEquals(2000, all.getLast().tick());
    amount("600", all.getLast().value());
    amount("200", all.getLast().profit());
    assertNotEquals(bins, PortfolioHistory.bins(trades, observations, 2040, 1000, 9, true));
  }

  @Test
  public void olderPageReplaysPriorTradesWithoutIncludingLaterSales() {
    var trades =
        List.of(trade(Side.BUY, 10, "1000", 0, 0), trade(Side.SELL, 10, "1200", 20000, 20000));
    var observations =
        List.of(
            new PriceObservation(10000, 110),
            new PriceObservation(18000, 120),
            new PriceObservation(20000, 120));
    var page = com.thaalur.quadstock.data.ChartWindow.history(30000, 1000, 9, 10L);
    var bins = PortfolioHistory.bins(trades, observations, page);
    var points = bins.stream().flatMap(b -> b.points().stream()).toList();
    assertEquals(2, points.size());
    assertEquals(10, points.getLast().shares());
    amount("200", points.getLast().profit());
    amount("0", points.getLast().sales());
  }
}
