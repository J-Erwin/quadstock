package com.thaalur.quadstock.data;

import static org.junit.Assert.*;

import org.junit.Test;

public class ChartWindowTest {
  @Test
  public void liveMovesWhileHistoricalBoundariesRemainFixed() {
    for (var range : TimeRange.values()) {
      long now = range.ticks() * 2;
      assertEquals(
          ChartWindow.create(now, range.ticks(), 9, false).intervals(),
          ChartWindow.create(now + 40, range.ticks(), 9, false).intervals());
      assertNotEquals(
          ChartWindow.create(now, range.ticks(), 9, true).intervals(),
          ChartWindow.create(now + 40, range.ticks(), 9, true).intervals());
    }
  }

  @Test
  public void liveIncludesBothEndpointsAndExcludesFuture() {
    var live = ChartWindow.create(2000, 1000, 9, true);
    assertEquals(0, live.column(1000));
    assertEquals(8, live.column(2000));
    assertEquals(-1, live.column(999));
    assertEquals(-1, live.column(2001));
    var origin = ChartWindow.create(0, 1000, 9, true);
    assertEquals(8, origin.column(0));
  }

  @Test
  public void nonDivisibleHistoricalBoundariesHaveNoGapsOrOverlap() {
    var layout = ChartWindow.create(2040, 1000, 9, false);
    for (long tick = layout.intervals().getFirst().start(); tick <= 2040; tick++) {
      int n = layout.column(tick);
      assertTrue(n >= 0);
      var b = layout.intervals().get(n);
      assertTrue(tick >= b.start() && tick < b.end());
      if (n > 0) assertEquals(layout.intervals().get(n - 1).end(), b.start());
    }
  }

  @Test
  public void historicalColumnsEachCoverOneFullSelectedPeriod() {
    for (var range : TimeRange.values()) {
      var page = ChartWindow.history(range.ticks() * 100 + 40, range.ticks(), 9, null);
      assertEquals(range.ticks() * 92, page.intervals().getFirst().start());
      for (var b : page.intervals()) assertEquals(range.ticks(), b.end() - b.start());
      assertEquals(8, page.column(range.ticks() * 100));
    }
  }

  @Test
  public void olderPageRemainsPinnedAcrossCalendarBoundaries() {
    var before = ChartWindow.history(100000, 1000, 9, 50L);
    var after = ChartWindow.history(110000, 1000, 9, 50L);
    assertEquals(before.intervals(), after.intervals());
    assertEquals(-1, after.column(59000));
    assertEquals(8, after.column(58999));
  }

  @Test
  public void pageClampsAtCalendarOriginAndPresent() {
    assertEquals(0, ChartWindow.history(100000, 1000, 9, -10L).intervals().getFirst().start());
    assertEquals(92000, ChartWindow.history(100000, 1000, 9, 200L).intervals().getFirst().start());
    var origin = ChartWindow.history(0, 1000, 9, null);
    assertEquals(0, origin.column(0));
    assertEquals(-1, origin.column(1));
  }

  @Test
  public void anchoredLiveBucketsKeepClosedObservationsWhenTheyShift() throws Exception {
    var market =
        LivingMarket.load(
            java.nio.file.Files.createTempDirectory("live-buckets"),
            0,
            com.thaalur.quadstock.TestMarkets.industry(),
            1);
    market.advanceTo(4000);
    var before = market.bars(ChartWindow.live(3000, 1000, 9));
    var after = market.bars(ChartWindow.live(3223, 1000, 9));
    int matched = 0;
    for (var old : before)
      if (old.endTick() <= 3000) {
        var retained = after.stream().filter(b -> b.startTick() == old.startTick()).findFirst();
        if (retained.isPresent()) {
          assertEquals(old, retained.get());
          matched++;
        }
      }
    assertTrue(matched >= 5);
    assertEquals(
        ChartWindow.live(3001, 1000, 9).intervals(), ChartWindow.live(3040, 1000, 9).intervals());
  }
}
