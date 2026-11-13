package com.thaalur.quadstock.notifications;

import static org.junit.Assert.*;

import com.thaalur.quadstock.data.MarketCalendar;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

public class ReportSessionsTest {
  @Test
  public void unsubscribeResubscribeDeliversAgainAndRepeatedSubscribeKeepsDeadline()
      throws Exception {
    var file = Files.createTempDirectory("report-session");
    var sessions = new ReportSessions(Subscriptions.load(file));
    UUID player = UUID.randomUUID();
    long day = MarketCalendar.DAY_TICKS;
    sessions.set(player, true, 100);
    var first = sessions.schedule(player, 100);
    assertTrue(first.briefingDue(100 + day));
    first.briefingSent(100 + day);
    sessions.set(player, false, 101 + day);
    assertNull(sessions.schedule(player, 101 + 2 * day));
    sessions.set(player, true, 200 + 2 * day);
    var resumed = sessions.schedule(player, 200 + 2 * day);
    sessions.set(player, true, 200 + 3 * day - 20);
    assertSame(resumed, sessions.schedule(player, 200 + 3 * day));
    assertFalse(resumed.briefingDue(200 + 3 * day - 1));
    assertTrue(resumed.briefingDue(200 + 3 * day));
    resumed.briefingSent(200 + 3 * day);
    assertFalse(resumed.briefingDue(200 + 3 * day + 20));
    assertTrue(resumed.briefingDue(200 + 4 * day));
    assertTrue(Subscriptions.load(file).contains(player));
  }

  @Test
  public void pendingNewsIsDiscardedOnLogoutAndUnsubscribeButRepeatedOptInKeepsIt()
      throws Exception {
    var sessions =
        new ReportSessions(Subscriptions.load(Files.createTempDirectory("news-session")));
    UUID player = UUID.randomUUID();
    var event =
        new com.thaalur.quadstock.data.LivingMarket.IndustryEvent(
            200, 300, "News", "Description", 0, "neutral", "generic", "neutral_1", 3, 2);
    sessions.set(player, true, 100);
    var first = sessions.schedule(player, 100);
    first.news().discover("CUSTOM", java.util.List.of(event), true, 200);
    sessions.set(player, true, 201);
    assertEquals(1, sessions.schedule(player, 201).news().pendingCount());
    sessions.forget(player);
    var rejoined = sessions.schedule(player, 250);
    rejoined.news().discover("CUSTOM", java.util.List.of(event), true, 250);
    assertFalse(rejoined.news().hasNews());
    assertTrue(sessions.subscribed(player));
    rejoined
        .news()
        .discover(
            "OTHER",
            java.util.List.of(
                new com.thaalur.quadstock.data.LivingMarket.IndustryEvent(
                    260, 360, "News", "Description", 0, "neutral", "generic", "neutral_1", 3, 2)),
            true,
            260);
    assertTrue(rejoined.news().hasNews());
    sessions.set(player, false, 270);
    sessions.set(player, true, 280);
    assertFalse(sessions.schedule(player, 280).news().hasNews());
  }

  @Test
  public void playersAreIndependentAndReconnectStartsNewWindow() throws Exception {
    var sessions =
        new ReportSessions(Subscriptions.load(Files.createTempDirectory("report-players")));
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    sessions.set(a, true, 100);
    sessions.set(b, true, 200);
    sessions.set(a, false, 300);
    sessions.retain(Set.of(b));
    assertEquals(200, sessions.schedule(b, 400).periodStart());
    sessions.forget(b);
    assertEquals(500, sessions.schedule(b, 500).periodStart());
    assertNull(sessions.schedule(a, 500));
  }
}
