package com.thaalur.quadstock.display;

import static org.junit.Assert.*;

import com.thaalur.quadstock.commerce.CommerceSignal;
import com.thaalur.quadstock.data.*;
import java.util.*;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.Test;

public class DisplayRendererTest {
  private LivingMarket.HistoryBin bin(long start, long end, double open, double close) {
    return new LivingMarket.HistoryBin(
        start,
        end,
        Optional.of(
            new PriceBar(start, end, open, Math.max(open, close), Math.min(open, close), close)),
        2);
  }

  private String plain(Component c) {
    return (c instanceof TextComponent t ? t.content() : "")
        + c.children().stream().map(this::plain).reduce("", String::concat);
  }

  @Test
  public void observedChangeUsesRecordedEndpointsAndExcludesGaps() {
    var bins =
        List.of(
            bin(0, 10, 80, 90),
            new LivingMarket.HistoryBin(10, 20, Optional.empty(), 0),
            bin(20, 30, 100, 110));
    assertEquals(37.5, DisplayRenderer.change(bins).orElseThrow(), 0.000001);
    var s =
        new DisplayRenderer.Snapshot(
            "Custom",
            999,
            30,
            bins,
            null,
            "neutral",
            new CommerceSignal(0).summary(0),
            "Unavailable");
    var result = DisplayRenderer.render(DisplayStoreTest.draft(), s, 240, 0);
    assertTrue(result.stream().map(this::plain).anyMatch(t -> t.contains("37.50%")));
    assertTrue(result.stream().map(this::plain).anyMatch(t -> t.contains("999.00")));
  }

  @Test
  public void noHistoryIsNotZeroReturn() {
    assertTrue(DisplayRenderer.change(List.of()).isEmpty());
    assertTrue(DisplayRenderer.change(List.of(bin(0, 0, 80, 80))).isEmpty());
    assertEquals(NamedTextColor.GRAY, DisplayRenderer.movement(-0.0001, 2));
    assertEquals(NamedTextColor.RED, DisplayRenderer.movement(-0.1, 2));
  }

  @Test
  public void flatSparklineHasGapsAndStableHeight() {
    var c =
        DisplayRenderer.spark(
            List.of(
                bin(0, 1, 5, 5),
                new LivingMarket.HistoryBin(1, 2, Optional.empty(), 0),
                bin(2, 3, 5, 5)));
    assertEquals("▄·▄", plain(c));
  }

  @Test
  public void allModesReuseUiWindows() {
    long now = 17 * MarketCalendar.DAY_TICKS;
    for (var mode : DisplayDefinition.Mode.values())
      assertEquals(
          mode == DisplayDefinition.Mode.LIVE
              ? ChartWindow.live(now, TimeRange.HOUR.ticks(), 9)
              : ChartWindow.history(now, mode.range().ticks(), 9, null),
          mode.window(now));
  }

  @Test
  public void allTypesRenderFourSignLinesAndHeadlineStylesPersist() {
    var d = DisplayStoreTest.draft().option("surface", "SIGN");
    var n =
        new LivingMarket.IndustryEvent(
            0,
            1000,
            "A long harvest headline for scrolling",
            "Details",
            .05,
            "positive",
            "generic",
            "id",
            6,
            1);
    var s =
        new DisplayRenderer.Snapshot(
            "Name",
            100,
            20,
            List.of(bin(0, 10, 100, 105)),
            n,
            "positive",
            new CommerceSignal(0).summary(0),
            "Disabled");
    for (var type : DisplayDefinition.Type.values()) {
      var lines = DisplayRenderer.render(d.option("type", type.name()), s, 90, 3000);
      assertEquals(4, lines.size());
      if (type == DisplayDefinition.Type.NEWS)
        assertEquals(NamedTextColor.GREEN, lines.get(2).color());
    }
  }

  @Test
  public void narrowNumericLinesRetainUnitsAndNineSparkPoints() {
    var d = DisplayStoreTest.draft().option("surface", "SIGN").option("type", "PRICE");
    var s =
        new DisplayRenderer.Snapshot(
            "Name",
            999999,
            30,
            List.of(bin(0, 1, 1, 999999)),
            null,
            "neutral",
            new CommerceSignal(0).summary(0),
            "QuickShop connected");
    var lines = DisplayRenderer.render(d, s, 60, 0);
    assertTrue(plain(lines.get(1)).endsWith("K"));
    assertTrue(plain(lines.get(2)).endsWith("%"));
    for (int i = 1; i < 4; i++) assertFalse(plain(lines.get(i)).contains("…"));
    var bins = new ArrayList<LivingMarket.HistoryBin>();
    for (int i = 0; i < 9; i++) bins.add(bin(i, i + 1, 5, 5 + i));
    String narrow = plain(DisplayRenderer.spark(bins, true));
    assertEquals(9, narrow.length());
    assertTrue(DisplayText.width(narrow, false) <= 60);
  }
}
