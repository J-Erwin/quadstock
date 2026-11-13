package com.thaalur.quadstock.data;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.Test;

public final class LivingMarketTest {
  @Test
  public void hourChartHasTwelveChangingSamplesAndNoCadenceDrift() throws Exception {
    Path dir = Files.createTempDirectory("quadstock-dense-");
    try {
      long start = 3 * MarketCalendar.MONTH_TICKS;
      LivingMarket market =
          LivingMarket.load(dir, start, com.thaalur.quadstock.TestMarkets.industry(), 1);
      market.advanceTo(start + MarketCalendar.HOUR_TICKS);
      assertEquals(13, market.observations().size()); // starting point + twelve updates
      assertEquals(start + MarketCalendar.HOUR_TICKS, market.observations().getLast().tick());
      assertTrue(
          market.observations().stream()
                  .map(LivingMarket.PriceObservation::price)
                  .distinct()
                  .count()
              > 5);
      assertTrue(
          market.bars(market.currentTick(), TimeRange.HOUR, 9).stream()
              .allMatch(b -> b.prices().isPresent()));
      market.advanceTo(start + MarketCalendar.DAY_TICKS);
      assertEquals(289, market.observations().size());
    } finally {
      remove(dir);
    }
  }

  @Test
  public void restartPreservesRandomSequenceEventsAndRecordedHistory() throws Exception {
    Path dir = Files.createTempDirectory("quadstock-live-");
    try {
      long start = MarketCalendar.INITIAL_TICKS + 37;
      LivingMarket original =
          LivingMarket.load(dir, start, com.thaalur.quadstock.TestMarkets.industry(), 1);
      assertEquals(1, original.observations().size());
      assertEquals(start, original.firstObservationTick());
      original.advanceTo(start + 27_431);
      original.save();
      LivingMarket resumed =
          LivingMarket.load(
              dir, original.currentTick(), com.thaalur.quadstock.TestMarkets.industry(), 1);
      assertEquals(original.observations(), resumed.observations());
      assertEquals(original.events(), resumed.events());
      assertEquals(original.currentPrice(), resumed.currentPrice(), 0);
      long later = start + 150_123;
      original.advanceTo(later);
      resumed.advanceTo(later);
      assertEquals(original.observations(), resumed.observations());
      assertEquals(original.events(), resumed.events());
      // Calendar may have been saved ahead of the market during a failed checkpoint.
      LivingMarket recovered =
          LivingMarket.load(dir, later, com.thaalur.quadstock.TestMarkets.industry(), 1);
      assertEquals(original.observations(), recovered.observations());
      assertEquals(original.events(), recovered.events());
      assertTrue(
          original.events().stream()
              .allMatch(e -> e.startTick() >= start && e.endTick() > e.startTick()));
      for (int i = 1; i < original.observations().size(); i++) {
        var before = original.observations().get(i - 1);
        var after = original.observations().get(i);
        assertEquals(LivingMarket.nextSampleAfter(before.tick()), after.tick());
        assertTrue(
            Math.abs(after.price() - before.price())
                <= before.price()
                        * original.events().stream()
                            .filter(
                                e -> after.tick() >= e.startTick() && after.tick() < e.endTick())
                            .mapToDouble(e -> e.maxMovePercent() / 100)
                            .findFirst()
                            .orElse(.005)
                        * (after.tick() - before.tick())
                        / MarketCalendar.HOUR_TICKS
                    + 1e-10);
      }
    } finally {
      remove(dir);
    }
  }

  @Test
  public void missingHistoryRemainsEmptyAndBarsPreserveActualExtremes() throws Exception {
    Path dir = Files.createTempDirectory("quadstock-history-");
    try {
      long start = 3 * MarketCalendar.MONTH_TICKS;
      LivingMarket market =
          LivingMarket.load(dir, start, com.thaalur.quadstock.TestMarkets.industry(), 1);
      assertEquals(
          8,
          market.bars(start, TimeRange.MONTH, 9).stream()
              .filter(b -> b.prices().isEmpty())
              .count());
      market.advanceTo(start + 25_500);
      for (TimeRange range : TimeRange.values()) {
        var bins = market.bars(market.currentTick(), range, 9);
        long total = 0;
        for (int i = 0; i < bins.size(); i++) {
          var bin = bins.get(i);
          boolean last = i == bins.size() - 1;
          var points =
              market.observations().stream()
                  .filter(
                      p ->
                          p.tick() >= bin.startTick()
                              && (p.tick() < bin.endTick() || last && p.tick() == bin.endTick()))
                  .toList();
          assertEquals(points.size(), bin.observations());
          assertEquals(points.isEmpty(), bin.prices().isEmpty());
          total += points.size();
          if (!points.isEmpty()) {
            var bar = bin.prices().orElseThrow();
            assertEquals(points.getFirst().tick(), bar.startTick());
            assertEquals(points.getLast().tick(), bar.endTick());
            assertEquals(
                points.stream()
                    .mapToDouble(LivingMarket.PriceObservation::price)
                    .max()
                    .orElseThrow(),
                bar.high(),
                0);
            assertEquals(
                points.stream()
                    .mapToDouble(LivingMarket.PriceObservation::price)
                    .min()
                    .orElseThrow(),
                bar.low(),
                0);
          }
        }
        long boundary = market.currentTick() - range.ticks();
        assertEquals(
            market.observations().stream().filter(p -> p.tick() >= boundary).count(), total);
      }
      assertTrue(
          market.bars(start - 1, TimeRange.DAY, 9).stream().allMatch(b -> b.prices().isEmpty()));
      assertThrows(
          IllegalArgumentException.class,
          () -> market.bars(market.currentTick() + 1, TimeRange.HOUR, 9));
      assertThrows(IllegalArgumentException.class, () -> market.advanceTo(start));
    } finally {
      remove(dir);
    }
  }

  @Test
  public void corruptedOrFutureStateFailsWithoutSilentlyResetting() throws Exception {
    Path dir = Files.createTempDirectory("quadstock-corrupt-");
    try {
      LivingMarket market =
          LivingMarket.load(dir, 50_000, com.thaalur.quadstock.TestMarkets.industry(), 1);
      market.advanceTo(60_000);
      market.save();
      assertThrows(
          IOException.class,
          () -> LivingMarket.load(dir, 59_999, com.thaalur.quadstock.TestMarkets.industry(), 1));
      byte[] bytes =
          com.thaalur.quadstock.persistence.StateStore.read(dir, "market/AGRI").orElseThrow();
      bytes[20] ^= 1;
      com.thaalur.quadstock.persistence.StateStore.write(dir, "market/AGRI", bytes);
      assertThrows(
          IOException.class,
          () -> LivingMarket.load(dir, 60_000, com.thaalur.quadstock.TestMarkets.industry(), 1));
      assertArrayEquals(
          bytes,
          com.thaalur.quadstock.persistence.StateStore.read(dir, "market/AGRI").orElseThrow());
      byte[] unsupported = market.snapshot();
      java.nio.ByteBuffer.wrap(unsupported).putInt(0);
      var checksum = new java.util.zip.CRC32();
      checksum.update(unsupported, 0, unsupported.length - Long.BYTES);
      java.nio.ByteBuffer.wrap(unsupported)
          .putLong(unsupported.length - Long.BYTES, checksum.getValue());
      com.thaalur.quadstock.persistence.StateStore.write(dir, "market/AGRI", unsupported);
      var failure =
          assertThrows(
              IOException.class,
              () ->
                  LivingMarket.load(dir, 60_000, com.thaalur.quadstock.TestMarkets.industry(), 1));
      assertTrue(failure.getMessage().contains("Unsupported market snapshot version"));
      assertArrayEquals(
          unsupported,
          com.thaalur.quadstock.persistence.StateStore.read(dir, "market/AGRI").orElseThrow());
      com.thaalur.quadstock.persistence.StateStore.write(dir, "market/AGRI", new byte[] {1, 2, 3});
      assertThrows(
          IOException.class,
          () -> LivingMarket.load(dir, 60_000, com.thaalur.quadstock.TestMarkets.industry(), 1));
    } finally {
      remove(dir);
    }
  }

  @Test
  public void alignedMarketHistoryStaysFixedBetweenObservationsAndDoesNotLeakFuture()
      throws Exception {
    Path dir = Files.createTempDirectory("quadstock-aligned-");
    try {
      LivingMarket market =
          LivingMarket.load(dir, 48000, com.thaalur.quadstock.TestMarkets.industry(), 1);
      market.advanceTo(100000);
      for (TimeRange range : TimeRange.values()) {
        assertEquals(market.bars(96001, range, 9, false), market.bars(96040, range, 9, false));
        var bins = market.bars(96001, range, 9, false);
        assertTrue(
            bins.stream().flatMap(b -> b.prices().stream()).allMatch(b -> b.endTick() <= 96001));
      }
      assertNotEquals(
          market.bars(96001, TimeRange.HOUR, 9, true), market.bars(96040, TimeRange.HOUR, 9, true));
    } finally {
      remove(dir);
    }
  }

  @Test
  public void historicalPageContainsOnlyItsNineFullPeriods() throws Exception {
    Path dir = Files.createTempDirectory("quadstock-page-");
    try {
      LivingMarket market =
          LivingMarket.load(dir, 48000, com.thaalur.quadstock.TestMarkets.industry(), 1);
      market.advanceTo(100000);
      var bins = market.bars(ChartWindow.history(100000, 1000, 9, 50L));
      assertEquals(50000, bins.getFirst().startTick());
      assertEquals(59000, bins.getLast().endTick());
      long expected =
          market.observations().stream().filter(p -> p.tick() >= 50000 && p.tick() < 59000).count();
      assertEquals(expected, bins.stream().mapToLong(b -> b.observations()).sum());
      for (var b : bins) assertEquals(1000, b.endTick() - b.startTick());
    } finally {
      remove(dir);
    }
  }

  private static void remove(Path directory) throws IOException {
    try (var paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }
}
