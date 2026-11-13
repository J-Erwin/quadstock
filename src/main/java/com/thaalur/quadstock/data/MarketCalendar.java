package com.thaalur.quadstock.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

/** Independent tick clock. Only the server scheduler advances it; no wall/world time is read. */
public final class MarketCalendar {
  public static final long HOUR_TICKS = 1_000;
  public static final long DAY_TICKS = 24 * HOUR_TICKS;
  public static final long WEEK_TICKS = 7 * DAY_TICKS;
  public static final long MONTH_TICKS = 28 * DAY_TICKS;

  /** New exchanges begin at Month 1 Day 1 00:00. */
  public static final long INITIAL_TICKS = 0;

  private static final String HEADER = "quadstock-calendar-v1";
  private final Path directory;
  private long ticks;

  private MarketCalendar(Path directory, long ticks) {
    this.directory = directory.toAbsolutePath();
    this.ticks = ticks;
  }

  /**
   * Loads the SQLite calendar checkpoint or uses INITIAL_TICKS when absent. Malformed saved time is
   * an I/O error.
   */
  public static MarketCalendar load(Path directory) throws IOException {
    var state = com.thaalur.quadstock.persistence.StateStore.read(directory, "calendar");
    if (state.isEmpty()) return new MarketCalendar(directory, INITIAL_TICKS);
    String[] lines = new String(state.get(), StandardCharsets.UTF_8).strip().split("\\R");
    try {
      if (lines.length != 2 || !HEADER.equals(lines[0]))
        throw new IllegalArgumentException("Invalid format");
      long ticks = Long.parseLong(lines[1]);
      if (ticks < 0) throw new IllegalArgumentException("Negative clock");
      return new MarketCalendar(directory, ticks);
    } catch (IllegalArgumentException e) {
      // Never silently reset history when saved state is damaged.
      throw new IOException("Invalid QuadStock calendar: " + directory, e);
    }
  }

  /**
   * Returns elapsed logical market ticks; this clock is independent of world daylight and wall
   * time.
   */
  public synchronized long ticks() {
    return ticks;
  }

  /**
   * Advances by a nonnegative delta; negative deltas and long overflow fail rather than wrapping.
   */
  public synchronized void addTicks(long delta) {
    if (delta < 0) throw new IllegalArgumentException("Cannot rewind market calendar");
    ticks = Math.addExact(ticks, delta);
  }

  /** Encodes the logical clock for a coordinated SQLite checkpoint. */
  public synchronized byte[] snapshot() {
    return (HEADER + "\n" + ticks + "\n").getBytes(StandardCharsets.UTF_8);
  }

  /** Commits this clock independently; runtime checkpoints save all markets atomically with it. */
  public synchronized void save() throws IOException {
    com.thaalur.quadstock.persistence.StateStore.write(directory, "calendar", snapshot());
  }

  /**
   * Formats a nonnegative tick as a one-based month/day and zero-based hour/minute in the market
   * calendar.
   */
  public static String format(long ticks) {
    if (ticks < 0) throw new IllegalArgumentException("Market ticks must be nonnegative");
    long month = ticks / MONTH_TICKS + 1;
    long day = ticks % MONTH_TICKS / DAY_TICKS + 1;
    long hour = ticks % DAY_TICKS / HOUR_TICKS;
    long minute = ticks % HOUR_TICKS * 60 / HOUR_TICKS;
    return String.format(Locale.ROOT, "Month %d Day %d %02d:%02d", month, day, hour, minute);
  }
}
