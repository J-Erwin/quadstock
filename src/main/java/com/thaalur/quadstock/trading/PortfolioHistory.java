package com.thaalur.quadstock.trading;

import com.thaalur.quadstock.data.ChartWindow;
import com.thaalur.quadstock.data.LivingMarket.PriceObservation;
import java.math.BigDecimal;
import java.util.*;

/**
 * Reconstruct observed account value and lifetime cash-flow-adjusted profit. No inferred prices.
 */
public final class PortfolioHistory {
  private PortfolioHistory() {}

  /**
   * One observed position value with cumulative committed purchase spending and sale proceeds.
   * Profit is value plus sales minus purchases; wallet cash is excluded.
   */
  public record Point(
      long tick, long shares, BigDecimal value, BigDecimal purchases, BigDecimal sales) {
    public BigDecimal profit() {
      return value.add(sales).subtract(purchases);
    }
  }

  /** Immutable observed points within one chart interval; an empty list is a history gap. */
  public record Bin(long start, long end, List<Point> points) {
    public Bin {
      points = List.copyOf(points);
    }
  }

  /**
   * Builds aligned subdivisions of a window. Input history must belong to one player and one
   * ticker, and observations must be chronological for that ticker.
   */
  public static List<Bin> bins(
      List<HoldingsLedger.Entry> history,
      List<PriceObservation> observations,
      long now,
      long window,
      int count) {
    return bins(history, observations, now, window, count, false);
  }

  /**
   * Builds rolling or aligned subdivisions. Filters to committed operations; no pre-trade or
   * missing-price values are fabricated. All times are market ticks.
   */
  public static List<Bin> bins(
      List<HoldingsLedger.Entry> history,
      List<PriceObservation> observations,
      long now,
      long window,
      int count,
      boolean live) {
    if (now < 0 || window <= 0 || count < 1 || count > 54)
      throw new IllegalArgumentException("Invalid history window");
    return bins(history, observations, ChartWindow.create(now, window, count, live));
  }

  /**
   * Replays a single player/ticker’s committed cash flows at actual price observations. Caller must
   * filter ticker and player first. Commit time determines when holdings affect values; all earlier
   * trades remain relevant even when price retention is shorter.
   */
  public static List<Bin> bins(
      List<HoldingsLedger.Entry> history, List<PriceObservation> observations, ChartWindow layout) {
    long now = layout.now();
    int count = layout.intervals().size();
    var trades =
        history.stream()
            .filter(e -> e.state() == HoldingsLedger.State.COMMITTED)
            .sorted(Comparator.comparingLong(e -> e.steps().getLast().tick()))
            .toList();
    List<List<Point>> points = new ArrayList<>();
    for (int i = 0; i < count; i++) points.add(new ArrayList<>());
    long shares = 0;
    BigDecimal buys = BigDecimal.ZERO, sales = BigDecimal.ZERO;
    int next = 0;
    for (var observation : observations) {
      if (observation.tick() > now) break;
      while (next < trades.size()
          && trades.get(next).steps().getLast().tick() <= observation.tick()) {
        var intent = trades.get(next++).intent();
        if (intent.side() == HoldingsLedger.Side.BUY) {
          shares = Math.addExact(shares, intent.shares());
          buys = buys.add(intent.amount());
        } else {
          shares -= intent.shares();
          sales = sales.add(intent.amount());
        }
      }
      int column = layout.column(observation.tick());
      if (column < 0 || next == 0) continue;
      points
          .get(column)
          .add(
              new Point(
                  observation.tick(),
                  shares,
                  BigDecimal.valueOf(observation.price()).multiply(BigDecimal.valueOf(shares)),
                  buys,
                  sales));
    }
    List<Bin> bins = new ArrayList<>();
    for (int i = 0; i < count; i++)
      bins.add(
          new Bin(
              layout.intervals().get(i).start(), layout.intervals().get(i).end(), points.get(i)));
    return List.copyOf(bins);
  }
}
