package com.thaalur.quadstock.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure estimates, never executable quotes or permission to move funds. */
public final class TradePreview {
  /**
   * Reserve-backed sales require market funds and ticker allowance; unlimited sales may create
   * currency.
   */
  public enum SettlementMode {
    RESERVE_BACKED,
    UNLIMITED_LIQUIDITY
  }

  /**
   * Purchase estimate using rounded unit price times shares. Wallet is rounded down to currency
   * precision.
   */
  public record Purchase(
      int shares,
      BigDecimal unitPrice,
      BigDecimal total,
      BigDecimal wallet,
      BigDecimal shortfall,
      boolean affordable) {}

  /**
   * Sale estimate. Reduced offered quantity requires a new explicit acceptance; proceeds may be
   * zero.
   */
  public record Sale(
      int requested,
      int offered,
      BigDecimal unitPrice,
      BigDecimal proceeds,
      boolean needsNewAcceptance) {}

  private TradePreview() {}

  private static void quantity(int shares) {
    if (shares < 1 || shares > 10000)
      throw new IllegalArgumentException("Choose 1–10,000 whole shares");
  }

  private static BigDecimal price(double mark, int digits) {
    if (digits < 0 || digits > 4 || !Double.isFinite(mark) || mark <= 0)
      throw new IllegalArgumentException("Unsupported precision or market price");
    BigDecimal price = BigDecimal.valueOf(mark).setScale(digits, RoundingMode.HALF_UP);
    if (price.signum() <= 0) throw new IllegalArgumentException("Rounded price must be positive");
    return price;
  }

  private static BigDecimal funds(BigDecimal value, int digits) {
    if (value == null || value.signum() < 0)
      throw new IllegalArgumentException("Invalid available funds");
    return value.setScale(digits, RoundingMode.DOWN);
  }

  /** Maximum affordable whole shares, using the same rounding as executable purchases. */
  public static int maximumPurchase(double mark, int digits, BigDecimal wallet, int limit) {
    quantity(limit);
    return funds(wallet, digits)
        .divideToIntegralValue(price(mark, digits))
        .min(BigDecimal.valueOf(limit))
        .intValueExact();
  }

  /**
   * Computes a read-only estimate for 1–10,000 shares. Unit price uses HALF_UP, wallet uses DOWN.
   * Invalid price, funds or precision fail; affordability does not reserve funds.
   */
  public static Purchase purchase(double mark, int shares, int digits, BigDecimal wallet) {
    quantity(shares);
    BigDecimal unit = price(mark, digits), total = unit.multiply(BigDecimal.valueOf(shares));
    BigDecimal available = funds(wallet, digits);
    return new Purchase(
        shares,
        unit,
        total,
        available,
        total.subtract(available).max(BigDecimal.ZERO),
        available.compareTo(total) >= 0);
  }

  /**
   * Computes a read-only sale offer for owned shares. Reserve mode caps the offered count by both
   * reserve and ticker allowance; unlimited mode offers the full requested count.
   */
  public static Sale sale(
      double mark,
      int requested,
      long holdings,
      int digits,
      SettlementMode mode,
      BigDecimal marketReserve,
      BigDecimal tickerRemaining) {
    quantity(requested);
    if (holdings < requested || mode == null)
      throw new IllegalArgumentException("Insufficient holdings or missing policy");
    BigDecimal unit = price(mark, digits);
    int offered = requested;
    if (mode == SettlementMode.RESERVE_BACKED) {
      BigDecimal capacity = funds(marketReserve, digits).min(funds(tickerRemaining, digits));
      offered =
          capacity.divideToIntegralValue(unit).min(BigDecimal.valueOf(requested)).intValueExact();
    }
    return new Sale(
        requested, offered, unit, unit.multiply(BigDecimal.valueOf(offered)), offered != requested);
  }
}
