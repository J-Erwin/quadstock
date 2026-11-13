package com.thaalur.quadstock.commerce;

import static org.junit.Assert.*;

import com.thaalur.quadstock.data.LivingMarket;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class CommerceSignalTest {
  private static UUID id(int n) {
    return new UUID(0, n);
  }

  private static CommerceObservations.Trade trade(
      int buyer, int seller, int units, String type, boolean virtual) {
    return new CommerceObservations.Trade(
        seller, "WHEAT", units, type, virtual ? null : id(seller), id(buyer), false, null, 10, 9.5);
  }

  private static void broad(CommerceSignal s, long tick) {
    for (int i = 0; i < 8; i++) s.record(tick, trade(i + 1, 100 + i / 2, 48, "SELLING", false));
  }

  @Test
  public void repetitionParticipantCapsAndEligibilityAreEnforced() {
    var s = new CommerceSignal(0);
    assertEquals(48, s.record(0, trade(1, 100, 6400, "SELLING", false)));
    assertEquals(0, s.record(1, trade(1, 100, 6400, "SELLING", false)));
    assertEquals(48, s.record(2, trade(2, 100, 6400, "SELLING", false)));
    assertEquals(0, s.record(3, trade(3, 100, 6400, "SELLING", false)));
    assertEquals(0, s.record(4, trade(3, 101, 64, "BUYING", false)));
    assertEquals(0, s.record(5, trade(3, 101, 64, "SELLING", true)));
    assertEquals(0, s.record(6, trade(3, 3, 64, "SELLING", false)));
    assertEquals(96, s.summary(6).units());
    assertEquals(0, s.advance(24000), 0);
  }

  @Test
  public void learningDiversityAndStaleEvidenceKeepInfluenceBounded() {
    var s = new CommerceSignal(0);
    broad(s, 1);
    assertEquals(0, s.advance(6000), 0);
    s.advance(24000); // Old evidence is almost expired.
    broad(s, 24001);
    for (long tick = 24083; tick <= 48083; tick += 83) {
      double value = s.advance(tick);
      assertTrue(value >= 0 && value <= .02);
    }
    double peak = s.summary(48083).adjustment();
    assertTrue(peak > 0);
    assertFalse(s.summary(48083).qualified());
    assertEquals(0, s.summary(48083).units());
    assertTrue(s.advance(96083) < peak / 100);
    var narrow = new CommerceSignal(0);
    narrow.record(24000, trade(1, 100, 48, "SELLING", false));
    assertEquals(0, narrow.advance(30000), 0);
  }

  @Test
  public void evidenceAndDecaySurviveSerialization() throws Exception {
    var s = new CommerceSignal(0);
    broad(s, 24000);
    s.advance(25000);
    var bytes = new ByteArrayOutputStream();
    s.write(new DataOutputStream(bytes));
    var restored =
        CommerceSignal.read(
            new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), 25000);
    assertEquals(s.summary(25000), restored.summary(25000));
    assertEquals(0, restored.record(25001, trade(1, 100, 99, "SELLING", false)));
    assertEquals(s.advance(26000), restored.advance(26000), 0);
    assertThrows(
        IOException.class,
        () ->
            CommerceSignal.read(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), 24000));
  }

  @Test
  public void livePricesRespondGraduallyAndRestartCatchupIsExact() throws Exception {
    Path a = Files.createTempDirectory("commerce-live"),
        b = Files.createTempDirectory("commerce-baseline");
    var active = LivingMarket.load(a, 0, com.thaalur.quadstock.TestMarkets.industry(), 1);
    var baseline = LivingMarket.load(b, 0, com.thaalur.quadstock.TestMarkets.industry(), 1);
    active.advanceTo(24000);
    baseline.advanceTo(24000);
    double before = active.currentPrice();
    for (int i = 0; i < 8; i++)
      active.recordCommerce(trade(i + 1, 100 + i / 2, 48, "SELLING", false));
    assertEquals(before, active.currentPrice(), 0);
    active.advanceTo(27000);
    active.save();
    active.advanceTo(36000);
    baseline.advanceTo(36000);
    var recovered = LivingMarket.load(a, 36000, com.thaalur.quadstock.TestMarkets.industry(), 1);
    assertEquals(active.observations(), recovered.observations());
    assertEquals(active.commerceSummary(), recovered.commerceSummary());
    assertTrue(active.currentPrice() > baseline.currentPrice());
  }

  @Test
  public void customDiversityRequirementsApplyAfterLearningAndAfterRestore() throws Exception {
    var low = new CommerceSignal.Requirements(1, 1, 2, 1);
    var signal = new CommerceSignal(0, low);
    signal.record(23000, trade(1, 100, 48, "SELLING", false));
    assertFalse(signal.summary(23999).qualified());
    assertTrue(signal.summary(24000).qualified());
    assertTrue(signal.advance(25000) > 0);
    var bytes = new ByteArrayOutputStream();
    signal.write(new DataOutputStream(bytes));
    for (var high :
        List.of(
            new CommerceSignal.Requirements(2, 1, 2, 1),
            new CommerceSignal.Requirements(1, 2, 2, 1),
            new CommerceSignal.Requirements(1, 1, 3, 1),
            new CommerceSignal.Requirements(1, 1, 2, 2))) {
      var restored =
          CommerceSignal.read(
              new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), 25000, high);
      assertFalse(restored.summary(25000).qualified());
      assertEquals(signal.summary(25000).adjustment(), restored.summary(25000).adjustment(), 0);
      assertTrue(restored.advance(26000) < signal.summary(25000).adjustment());
    }
  }

  @Test
  public void percentageRescalesSavedEvidenceWithoutRewritingPastPrices() throws Exception {
    Path root = Files.createTempDirectory("commerce-percentage-");
    var requirements = new CommerceSignal.Requirements(1, 1, 2, 1);
    var industry = com.thaalur.quadstock.TestMarkets.industry();
    var market = LivingMarket.load(root, 0, industry, 1, requirements);
    market.advanceTo(24000);
    market.recordCommerce(trade(1, 100, 48, "SELLING", false));
    market.advanceTo(26000);
    market.save();
    double prior = market.commerceSummary().adjustment();
    assertTrue(prior > 0);
    var stronger = LivingMarket.load(root, 26000, industry, 2.5, requirements);
    assertEquals(5, stronger.commerceCapPercent(), 0);
    assertEquals(prior * 2.5, stronger.commerceSummary().adjustment(), 1e-12);
    assertEquals(market.observations(), stronger.observations());
    var zero = LivingMarket.load(root, 26000, industry, 0, requirements);
    assertEquals(0, zero.commerceSummary().adjustment(), 0);
    assertEquals(market.observations(), zero.observations());
  }
}
