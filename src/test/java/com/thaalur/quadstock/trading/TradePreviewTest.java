package com.thaalur.quadstock.trading;

import static com.thaalur.quadstock.trading.TradePreview.SettlementMode.*;
import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.Test;

public class TradePreviewTest {
  private static BigDecimal money(String n) {
    return new BigDecimal(n);
  }

  @Test
  public void maximumPurchaseMatchesQuoteRoundingAndCapsBeforeIntegerConversion() {
    assertEquals(2, TradePreview.maximumPurchase(10.005, 2, money("30.029"), 10000));
    assertEquals(3, TradePreview.maximumPurchase(10.005, 2, money("30.03"), 10000));
    assertEquals(0, TradePreview.maximumPurchase(10, 2, money("9.999"), 10000));
    assertEquals(0, TradePreview.maximumPurchase(10, 2, BigDecimal.ZERO, 10000));
    assertEquals(17, TradePreview.maximumPurchase(1, 2, money("99999999999999999999"), 17));
    assertEquals(2, TradePreview.maximumPurchase(10.4, 0, money("20.9"), 10000));
    assertEquals(2, TradePreview.maximumPurchase(10.12344, 4, money("20.2468"), 10000));
    assertThrows(
        IllegalArgumentException.class,
        () -> TradePreview.maximumPurchase(10, 2, money("-1"), 100));
  }

  @Test
  public void roundsUnitPriceBeforeMultiplicationAndDoesNotRoundWalletUp() {
    var p = TradePreview.purchase(10.005, 3, 2, money("30.029"));
    assertEquals(money("10.01"), p.unitPrice());
    assertEquals(money("30.03"), p.total());
    assertEquals(money("30.02"), p.wallet());
    assertEquals(money("0.01"), p.shortfall());
    assertFalse(p.affordable());
    assertTrue(TradePreview.purchase(10.005, 3, 2, money("30.03")).affordable());
  }

  @Test
  public void supportsWholeUnitAndFourDecimalCurrencies() {
    assertEquals(money("20"), TradePreview.purchase(10.4, 2, 0, money("20")).total());
    assertEquals(money("20.2468"), TradePreview.purchase(10.12344, 2, 4, money("30")).total());
  }

  @Test
  public void sameSaleDiffersOnlyBySettlementCapacity() {
    var reserve =
        TradePreview.sale(100, 10, 10, 2, RESERVE_BACKED, money("750"), Optional.of(money("500")));
    var unlimited =
        TradePreview.sale(100, 10, 10, 2, UNLIMITED_LIQUIDITY, BigDecimal.ZERO, Optional.empty());
    assertEquals(5, reserve.offered());
    assertEquals(money("500.00"), reserve.proceeds());
    assertTrue(reserve.needsNewAcceptance());
    assertEquals(10, unlimited.offered());
    assertEquals(money("1000.00"), unlimited.proceeds());
    assertFalse(unlimited.needsNewAcceptance());
  }

  @Test
  public void unlimitedAllowanceDistinguishesPartialExhaustedAndUncapped() {
    var partial =
        TradePreview.sale(
            100, 10, 10, 2, UNLIMITED_LIQUIDITY, BigDecimal.ZERO, Optional.of(money("250")));
    assertEquals(2, partial.offered());
    assertTrue(partial.needsNewAcceptance());
    assertEquals(money("200.00"), partial.proceeds());
    assertEquals(
        0,
        TradePreview.sale(
                100, 10, 10, 2, UNLIMITED_LIQUIDITY, BigDecimal.ZERO, Optional.of(BigDecimal.ZERO))
            .offered());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TradePreview.sale(
                100, 10, 10, 2, UNLIMITED_LIQUIDITY, BigDecimal.ZERO, Optional.of(money("-1"))));
  }

  @Test
  public void depletedReserveCannotPromiseEvenOneShare() {
    var sale =
        TradePreview.sale(
            100, 10, 10, 2, RESERVE_BACKED, money("99.99"), Optional.of(money("500")));
    assertEquals(0, sale.offered());
    assertEquals(0, sale.proceeds().signum());
    assertTrue(sale.needsNewAcceptance());
    assertEquals(
        7,
        TradePreview.sale(100, 10, 10, 2, RESERVE_BACKED, money("750"), Optional.of(money("900")))
            .offered());
  }

  @Test
  public void rejectsInvalidAmountsQuantitiesAndMissingHoldings() {
    for (double price : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
      assertThrows(
          IllegalArgumentException.class, () -> TradePreview.purchase(price, 1, 2, money("100")));
    for (int q : new int[] {0, -1, 10001})
      assertThrows(
          IllegalArgumentException.class, () -> TradePreview.purchase(100, q, 2, money("100")));
    assertThrows(
        IllegalArgumentException.class, () -> TradePreview.purchase(100, 1, 5, money("100")));
    assertThrows(
        IllegalArgumentException.class, () -> TradePreview.purchase(100, 1, 2, money("-1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> TradePreview.sale(100, 10, 9, 2, UNLIMITED_LIQUIDITY, null, null));
    assertThrows(
        IllegalArgumentException.class, () -> TradePreview.sale(100, 10, 10, 2, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TradePreview.sale(
                100, 10, 10, 2, RESERVE_BACKED, money("-1"), Optional.of(money("100"))));
  }
}
