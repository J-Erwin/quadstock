package com.thaalur.quadstock.notifications;

import static org.junit.Assert.*;

import com.thaalur.quadstock.data.MarketCalendar;
import org.junit.Test;

public class ReportScheduleTest {
  @Test
  public void briefingsRequireAFullMarketDayAndDoNotBurst() {
    var schedule = new ReportSchedule(100);
    assertFalse(schedule.briefingDue(100 + MarketCalendar.DAY_TICKS - 1));
    assertTrue(schedule.briefingDue(100 + MarketCalendar.DAY_TICKS));
    schedule.briefingSent(100 + 3 * MarketCalendar.DAY_TICKS);
    assertFalse(schedule.briefingDue(100 + 3 * MarketCalendar.DAY_TICKS));
  }

  @Test
  public void pendingNewsSurvivesCooldownAndOldEventsAreNotReplayed() {
    var schedule = new ReportSchedule(1000);
    var event =
        new com.thaalur.quadstock.data.LivingMarket.IndustryEvent(
            1200, 2000, "News", "Description", 0, "neutral", "generic", "neutral_1", 3, 2);
    schedule.news().discover("ONE", java.util.List.of(event), true, 1200);
    assertTrue(schedule.newsDue(1200));
    schedule.news().delivered(schedule.news().batch(4), 0);
    schedule.newsSent(1200);
    schedule.news().discover("TWO", java.util.List.of(event), true, 1201);
    assertFalse(schedule.newsDue(1201));
    assertEquals(1, schedule.news().pendingCount());
    assertTrue(schedule.newsDue(3200));
    var reconnect = new ReportSchedule(4000);
    reconnect.news().discover("TWO", java.util.List.of(event), true, 4001);
    assertFalse(reconnect.newsDue(4001));
  }
}
