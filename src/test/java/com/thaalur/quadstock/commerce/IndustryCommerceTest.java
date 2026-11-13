package com.thaalur.quadstock.commerce;

import static org.junit.Assert.*;

import com.thaalur.quadstock.config.MarketSettings.Industry;
import com.thaalur.quadstock.data.LivingMarket;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class IndustryCommerceTest {
  @Test
  public void mappedIndustryRespondsWhileUnrelatedIndustryIgnoresSameSale() throws Exception {
    var mining =
        new Industry(
            "MINE",
            "OVERWORLD",
            "Mining",
            "RAW_IRON",
            Set.of("RAW_IRON"),
            100,
            1,
            true,
            true,
            null);
    var fishing =
        new Industry(
            "FISH", "OVERWORLD", "Fishing", "COD", Set.of("COD"), 100, 1, true, true, null);
    var m = LivingMarket.load(Files.createTempDirectory("mining-"), 0, mining, 1);
    var f = LivingMarket.load(Files.createTempDirectory("fishing-"), 0, fishing, 1);
    m.advanceTo(24000);
    f.advanceTo(24000);
    for (int i = 0; i < 8; i++) {
      var sale =
          new CommerceObservations.Trade(
              i,
              "RAW_IRON",
              48,
              "SELLING",
              new UUID(0, 100 + i / 2),
              new UUID(0, i + 1),
              false,
              null,
              10,
              9.5);
      assertEquals(48, m.recordCommerce(sale));
      assertEquals(0, f.recordCommerce(sale));
    }
    m.advanceTo(30000);
    f.advanceTo(30000);
    assertTrue(m.commerceSummary().adjustment() > 0);
    assertEquals(0, f.commerceSummary().adjustment(), 0);
  }

  @Test
  public void zeroInfluenceAndDisabledEventsKeepReferenceStableWithoutNoise() throws Exception {
    var config =
        new Industry(
            "MINE",
            "OVERWORLD",
            "Mining",
            "RAW_IRON",
            Set.of("RAW_IRON"),
            150,
            0,
            false,
            true,
            null);
    Path path = Files.createTempDirectory("quiet-");
    var m = LivingMarket.load(path, 0, config, 0);
    m.advanceTo(48000);
    m.save();
    assertEquals(150, m.currentPrice(), 0);
    assertTrue(m.events().isEmpty());
    assertTrue(m.activeEvent().isEmpty());
    var resumed = LivingMarket.load(path, 48000, config, 0);
    assertEquals(m.observations(), resumed.observations());
  }
}
