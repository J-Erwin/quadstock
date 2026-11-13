package com.thaalur.quadstock.data;

/** Fixed market-calendar durations used as historical column widths or rolling windows. */
public enum TimeRange {
  HOUR(MarketCalendar.HOUR_TICKS, "1 hour"),
  DAY(MarketCalendar.DAY_TICKS, "1 day"),
  WEEK(MarketCalendar.WEEK_TICKS, "1 week"),
  MONTH(MarketCalendar.MONTH_TICKS, "1 month");

  private final long ticks;
  private final String label;

  TimeRange(long ticks, String label) {
    this.ticks = ticks;
    this.label = label;
  }

  /** Returns the exact duration in logical market ticks. */
  public long ticks() {
    return ticks;
  }

  /** Returns the English duration label used by menus. */
  public String label() {
    return label;
  }
}
