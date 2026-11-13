package com.thaalur.quadstock.notifications;

import static org.junit.Assert.*;

import com.thaalur.quadstock.data.LivingMarket.IndustryEvent;
import java.util.*;
import org.junit.Test;

public class PortfolioNewsTest {
  private IndustryEvent event(long tick, boolean special) {
    return new IndustryEvent(
        tick,
        tick + 100,
        "Headline",
        "Description",
        -.1,
        special ? "special-negative" : "negative",
        "generic",
        "negative_1",
        3,
        2);
  }

  @Test
  public void simultaneousIndustriesAreIndependentAndExpiredNewsCanBeDelivered() {
    var news = new PortfolioNews(100);
    news.discover("B", List.of(event(200, false)), true, 500);
    news.discover("A", List.of(event(200, false)), true, 500);
    news.discover("C", List.of(event(300, true)), true, 500);
    var batch = news.batch(2);
    assertEquals(List.of("C", "A"), batch.stream().map(PortfolioNews.Item::ticker).toList());
    news.delivered(batch, 0);
    assertEquals(1, news.pendingCount());
    news.discover("A", List.of(event(200, false)), true, 600);
    assertEquals(1, news.pendingCount());
    assertEquals("B", news.batch(1).getFirst().ticker());
  }

  @Test
  public void soldHoldingsAndPreLoginEventsDoNotReplay() {
    var news = new PortfolioNews(100);
    news.discover("A", List.of(event(99, true), event(200, false)), true, 300);
    assertEquals(1, news.pendingCount());
    news.discover("A", List.of(event(400, false)), false, 500);
    assertEquals(0, news.pendingCount());
    news.discover("A", List.of(event(200, false), event(400, false)), true, 600);
    assertFalse(news.hasNews());
  }

  @Test
  public void overflowRetainsSpecialsAndNewestOrdinaryNewsAcrossDiscoveryOrder() {
    var news = new PortfolioNews(0);
    var newer = new ArrayList<IndustryEvent>();
    for (int i = 100; i < 227; i++) newer.add(event(i, false));
    news.discover("NEW", newer, true, 300);
    news.discover("SPECIAL", List.of(event(1, true)), true, 300);
    news.discover("OLD", List.of(event(2, false)), true, 300);
    assertEquals(128, news.pendingCount());
    assertEquals(1, news.omittedCount());
    var batch = news.batch(2);
    assertEquals(
        List.of("SPECIAL", "NEW"), batch.stream().map(PortfolioNews.Item::ticker).toList());
    assertEquals(100, batch.get(1).event().startTick());
    news.discover("OLD", List.of(), false, 301);
    assertEquals(0, news.omittedCount());
  }

  @Test
  public void queueBoundsAreExplicitAndOnlyDeliveredItemsAreAcknowledged() {
    var news = new PortfolioNews(0);
    var events = new ArrayList<IndustryEvent>();
    for (int i = 1; i <= 140; i++) events.add(event(i, false));
    news.discover("A", events, true, 200);
    assertEquals(PortfolioNews.CAPACITY, news.pendingCount());
    assertEquals(12, news.omittedCount());
    var batch = news.batch(4);
    assertEquals(128, news.pendingCount()); // Reading is not delivery.
    news.delivered(batch, news.omittedCount());
    assertEquals(124, news.pendingCount());
    assertEquals(0, news.omittedCount());
  }
}
