package com.thaalur.quadstock.trading;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Shared mark-to-market valuation for inventory and Tab; never performs payments or writes. */
public record PortfolioSnapshot(
    List<Holding> holdings, BigDecimal value, BigDecimal cost, BigDecimal realized) {
  public PortfolioSnapshot {
    holdings = List.copyOf(holdings);
  }

  /** One configured industry and its already committed ledger position. */
  public record Source(
      String ticker, String name, String icon, HoldingsLedger.Position position, double mark) {}

  /** A currently owned position, retaining full precision until presentation. */
  public record Holding(
      String ticker, String name, String icon, long shares, BigDecimal value, BigDecimal cost) {
    public BigDecimal unrealized() {
      return value.subtract(cost);
    }
  }

  public BigDecimal unrealized() {
    return value.subtract(cost);
  }

  public static PortfolioSnapshot capture(Collection<Source> sources) {
    var holdings = new ArrayList<Holding>();
    BigDecimal value = BigDecimal.ZERO, cost = BigDecimal.ZERO, realized = BigDecimal.ZERO;
    for (var source : sources) {
      var p = source.position();
      realized = realized.add(p.realized());
      if (p.shares() == 0) continue;
      if (!Double.isFinite(source.mark()) || source.mark() < 0)
        throw new IllegalArgumentException("Invalid market mark");
      BigDecimal current =
          BigDecimal.valueOf(source.mark()).multiply(BigDecimal.valueOf(p.shares()));
      holdings.add(
          new Holding(
              source.ticker(), source.name(), source.icon(), p.shares(), current, p.cost()));
      value = value.add(current);
      cost = cost.add(p.cost());
    }
    return new PortfolioSnapshot(holdings, value, cost, realized);
  }
}
