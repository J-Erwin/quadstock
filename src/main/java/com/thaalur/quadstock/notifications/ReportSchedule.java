package com.thaalur.quadstock.notifications;

import com.thaalur.quadstock.data.MarketCalendar;

/** Per-online-session cursor: no missed-message replay after login or restart. */
public final class ReportSchedule {
  public static final long EVENT_COOLDOWN = 2 * MarketCalendar.HOUR_TICKS;
  private long lastBriefing, lastAlert;
  private final long interval, cooldown;
  private final PortfolioNews news;

  /** Starts an online cursor with a daily briefing interval and two-hour event cooldown. */
  public ReportSchedule(long tick) {
    this(tick, MarketCalendar.DAY_TICKS, EVENT_COOLDOWN);
  }

  /**
   * Starts a cursor at tick with positive market-tick durations. Events at or before tick are
   * treated as already seen; reconnect does not replay old news.
   */
  public ReportSchedule(long tick, long interval, long cooldown) {
    if (interval <= 0 || cooldown <= 0)
      throw new IllegalArgumentException("Report intervals must be positive");
    this.interval = interval;
    this.cooldown = cooldown;
    lastBriefing = tick;
    news = new PortfolioNews(tick);
    lastAlert = tick - cooldown;
  }

  /** Reports whether the interval has elapsed; callers must supply monotonic market ticks. */
  public boolean briefingDue(long tick) {
    return tick - lastBriefing >= interval;
  }

  /** Returns the last briefing/session-start tick. */
  public long periodStart() {
    return lastBriefing;
  }

  /**
   * Returns the scheduled market tick for the next briefing; no wall-clock conversion is applied.
   */
  public long nextBriefingTick() {
    return lastBriefing + interval;
  }

  /**
   * Advances the briefing cursor after delivery, dropping any missed intervals rather than
   * replaying them.
   */
  public void briefingSent(long tick) {
    lastBriefing = tick;
  }

  public PortfolioNews news() {
    return news;
  }

  /** A delivery slot can include all industries; pending events survive the shared cooldown. */
  public boolean newsDue(long tick) {
    return news.hasNews() && tick - lastAlert >= cooldown;
  }

  public void newsSent(long tick) {
    lastAlert = tick;
  }
}
