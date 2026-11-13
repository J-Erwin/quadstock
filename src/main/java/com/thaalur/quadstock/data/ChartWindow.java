package com.thaalur.quadstock.data;

import java.util.*;

/** Shared time buckets for rolling live charts and calendar-aligned history. */
public record ChartWindow(long now, boolean live, List<Interval> intervals) {
  /**
   * Column boundaries in market ticks. Historical intervals are half-open; only the final live
   * column includes its end.
   */
  public record Interval(long start, long end) {}

  /** Copies the interval list; use the factories to obtain validated layouts. */
  public ChartWindow {
    intervals = List.copyOf(intervals);
  }

  /**
   * Divides a rolling window or aligned window into count columns. Live mode clips the start at
   * tick zero; non-live mode aligns subdivisions of window. For one full period per historical
   * column, use history instead. All durations and timestamps are market ticks.
   */
  public static ChartWindow create(long now, long window, int count, boolean live) {
    if (now < 0 || window <= 0 || count < 1 || count > 128)
      throw new IllegalArgumentException("Invalid chart window");
    var intervals = new ArrayList<Interval>();
    if (live) {
      long start = Math.max(0, now - window), span = now - start;
      for (int i = 0; i < count; i++)
        intervals.add(new Interval(start + span * i / count, start + span * (i + 1) / count));
    } else {
      long first = Math.max(0, Math.multiplyExact(now, count) / window - count + 1);
      for (int i = 0; i < count; i++)
        intervals.add(
            new Interval(
                boundary(first + i, window, count), boundary(first + i + 1, window, count)));
    }
    return new ChartWindow(now, live, intervals);
  }

  /**
   * Live view with calendar-anchored buckets. A completed bucket retains exactly the same
   * observations when it shifts left; only the newest bucket is still forming. Window is the total
   * duration of count buckets, including the unfinished current bucket.
   */
  public static ChartWindow live(long now, long window, int count) {
    return new ChartWindow(now, true, create(now, window, count, false).intervals());
  }

  /** One complete calendar period per column. A null first period follows the present. */
  public static ChartWindow history(long now, long period, int count, Long firstPeriod) {
    if (now < 0 || period <= 0 || count < 1 || count > 128)
      throw new IllegalArgumentException("Invalid history page");
    long latest = Math.max(0, now / period - count + 1);
    long first = firstPeriod == null ? latest : Math.max(0, Math.min(firstPeriod, latest));
    var intervals = new ArrayList<Interval>();
    for (int i = 0; i < count; i++)
      intervals.add(
          new Interval(
              Math.multiplyExact(first + i, period), Math.multiplyExact(first + i + 1, period)));
    return new ChartWindow(now, false, intervals);
  }

  /**
   * Returns the column containing tick, or -1 for a future, negative or out-of-window observation.
   */
  public int column(long tick) {
    if (tick < 0 || tick > now) return -1;
    for (int i = 0; i < intervals.size(); i++) {
      var b = intervals.get(i);
      if (tick >= b.start && (tick < b.end || live && i == intervals.size() - 1 && tick == b.end))
        return i;
    }
    return -1;
  }

  private static long boundary(long bucket, long window, int count) {
    long n = Math.multiplyExact(bucket, window);
    return n / count + (n % count == 0 ? 0 : 1);
  }
}
