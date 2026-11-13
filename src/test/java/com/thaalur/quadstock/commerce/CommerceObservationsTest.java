package com.thaalur.quadstock.commerce;

import static com.thaalur.quadstock.commerce.CommerceObservations.Classification.*;
import static org.junit.Assert.*;

import java.util.UUID;
import org.junit.Test;

public class CommerceObservationsTest {
  private final UUID owner = UUID.randomUUID(), customer = UUID.randomUUID();

  private CommerceObservations.Trade trade(
      String material,
      String type,
      UUID customer,
      boolean unlimited,
      int units,
      double gross,
      String currency) {
    return new CommerceObservations.Trade(
        7, material, units, type, owner, customer, unlimited, currency, gross, 9);
  }

  @Test
  public void preservesUnitsAndDistinguishesBuyerFromSeller() {
    var selling = trade("WHEAT", "SELLING", customer, false, 64, 10, null);
    assertEquals(
        CANDIDATE,
        CommerceObservations.classify(
            selling, java.util.Set.of("WHEAT", "CARROT", "POTATO", "BEETROOT")));
    assertEquals(customer, selling.buyer());
    assertEquals(owner, selling.seller());
    var buying = trade("WHEAT", "BUYING", customer, false, 64, 10, null);
    assertEquals(
        CANDIDATE,
        CommerceObservations.classify(
            buying, java.util.Set.of("WHEAT", "CARROT", "POTATO", "BEETROOT")));
    assertEquals(owner, buying.buyer());
    assertEquals(customer, buying.seller());
    var observations =
        new CommerceObservations(java.util.Set.of("WHEAT", "CARROT", "POTATO", "BEETROOT"));
    observations.record(1234, selling);
    assertEquals(64, observations.recent().getFirst().trade().units());
    assertEquals(1234, observations.recent().getFirst().marketTick());
  }

  @Test
  public void excludesUnsupportedOrUntrustworthyInputs() {
    assertEquals(
        SELF_TRADE,
        CommerceObservations.classify(
            trade("WHEAT", "SELLING", owner, false, 1, 10, null),
            java.util.Set.of("WHEAT", "CARROT", "POTATO", "BEETROOT")));
    assertEquals(
        NON_PLAYER,
        CommerceObservations.classify(
            trade("WHEAT", "SELLING", null, false, 1, 10, null),
            java.util.Set.of("WHEAT", "CARROT", "POTATO", "BEETROOT")));
    assertEquals(
        UNLIMITED_SHOP,
        CommerceObservations.classify(
            trade("WHEAT", "SELLING", customer, true, 1, 10, null),
            java.util.Set.of("WHEAT", "CARROT", "POTATO", "BEETROOT")));
    assertEquals(
        UNRELATED_ITEM,
        CommerceObservations.classify(
            trade("DIAMOND", "SELLING", customer, false, 1, 10, null),
            java.util.Set.of("WHEAT", "CARROT", "POTATO", "BEETROOT")));
    assertEquals(
        UNSUPPORTED_DIRECTION,
        CommerceObservations.classify(
            trade("WHEAT", "CUSTOM", customer, false, 1, 10, null),
            java.util.Set.of("WHEAT", "CARROT", "POTATO", "BEETROOT")));
    assertEquals(
        CUSTOM_CURRENCY,
        CommerceObservations.classify(
            trade("WHEAT", "SELLING", customer, false, 1, 10, "gems"),
            java.util.Set.of("WHEAT", "CARROT", "POTATO", "BEETROOT")));
    for (double gross : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, 8})
      assertEquals(
          INVALID_AMOUNT,
          CommerceObservations.classify(
              trade("WHEAT", "SELLING", customer, false, 1, gross, null),
              java.util.Set.of("WHEAT", "CARROT", "POTATO", "BEETROOT")));
    assertEquals(
        INVALID_AMOUNT,
        CommerceObservations.classify(
            trade("WHEAT", "SELLING", customer, false, -1, 10, null),
            java.util.Set.of("WHEAT", "CARROT", "POTATO", "BEETROOT")));
  }

  @Test
  public void boundedSessionHistoryDoesNotDeduplicateLegitimateIdenticalPurchases() {
    var observations =
        new CommerceObservations(java.util.Set.of("WHEAT", "CARROT", "POTATO", "BEETROOT"));
    for (int i = 0; i < 105; i++)
      observations.record(i, trade("WHEAT", "SELLING", customer, false, 64, 10, ""));
    assertEquals(105, observations.total());
    assertEquals(105, observations.candidates());
    assertEquals(100, observations.recent().size());
    assertEquals(5, observations.recent().getFirst().marketTick());
    assertEquals(
        0,
        new CommerceObservations(java.util.Set.of("WHEAT", "CARROT", "POTATO", "BEETROOT"))
            .total());
  }
}
