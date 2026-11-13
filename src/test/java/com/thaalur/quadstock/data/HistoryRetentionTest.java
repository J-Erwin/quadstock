package com.thaalur.quadstock.data;

import static org.junit.Assert.*;

import com.thaalur.quadstock.commerce.CommerceSignal;
import com.thaalur.quadstock.config.MarketSettings;
import com.thaalur.quadstock.events.StoryCatalog;
import java.nio.file.Path;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class HistoryRetentionTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private MarketSettings.Industry industry() {
    return new MarketSettings.Industry(
        "CUSTOM",
        "EXCHANGE",
        "Retention test",
        "STONE",
        Set.of("STONE"),
        100,
        1,
        false,
        true,
        null);
  }

  private LivingMarket load(Path dir, long tick, int months) throws Exception {
    return LivingMarket.load(
        dir,
        tick,
        industry(),
        0,
        CommerceSignal.Requirements.DEFAULT,
        StoryCatalog.bundled(),
        months);
  }

  @Test
  public void upgradingThreeMonthSnapshotPreservesAllSamplesAndGrowsWithoutBackfill()
      throws Exception {
    var dir = temporary.getRoot().toPath();
    long month = MarketCalendar.MONTH_TICKS;
    var old = load(dir, 0, 3);
    old.advanceTo(5 * month);
    old.save();
    var before = old.observations();
    var events = old.events();
    double price = old.currentPrice();
    byte[] oldBytes = old.snapshot();
    var upgraded = load(dir, 5 * month, 12);
    assertEquals(before, upgraded.observations());
    assertEquals(events, upgraded.events());
    assertEquals(price, upgraded.currentPrice(), 0);
    assertArrayEquals(oldBytes, upgraded.snapshot());
    upgraded.advanceTo(6 * month);
    assertEquals(2 * month, upgraded.firstObservationTick());
    assertEquals(before, upgraded.observations().subList(0, before.size()));
    assertTrue(upgraded.observations().size() > before.size());
    upgraded.save();
    assertEquals(upgraded.observations(), load(dir, 6 * month, 12).observations());
  }

  @Test
  public void fullYearRemainsBoundedAndReloadsWithinExistingSnapshotLimits() throws Exception {
    var dir = temporary.getRoot().toPath();
    long month = MarketCalendar.MONTH_TICKS;
    var market = load(dir, 0, 12);
    market.advanceTo(13 * month);
    assertEquals(month, market.firstObservationTick());
    assertEquals(12 * 28 * 24 * 12 + 1, market.observations().size());
    assertTrue(market.snapshot().length < 10_000_000);
    market.save();
    assertEquals(market.observations(), load(dir, 13 * month, 12).observations());
    var reduced = load(dir, 13 * month, 3);
    assertEquals(10 * month, reduced.firstObservationTick());
    assertEquals(market.currentPrice(), reduced.currentPrice(), 0);
    assertEquals(market.observations().getLast(), reduced.observations().getLast());
  }

  @Test
  public void invalidPoliciesFailBeforeCreatingSnapshots() {
    for (int months : List.of(0, -1, 13))
      assertThrows(
          IllegalArgumentException.class, () -> load(temporary.getRoot().toPath(), 0, months));
  }
}
