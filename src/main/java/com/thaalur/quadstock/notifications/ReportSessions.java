package com.thaalur.quadstock.notifications;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Owns preference transitions and online delivery cursors together. */
public final class ReportSessions {
  private final Subscriptions subscriptions;
  private long interval = 24000, cooldown = 2000;

  /**
   * Sets market-tick cadence for newly created cursors and forgets all online schedules. Caller
   * supplies positive validated durations; durable preferences are untouched.
   */
  public void configure(long interval, long cooldown) {
    this.interval = interval;
    this.cooldown = cooldown;
    online.clear();
  }

  private final Map<UUID, ReportSchedule> online = new HashMap<>();

  /** Binds the durable preference store; this mutable session map is server-thread confined. */
  public ReportSessions(Subscriptions subscriptions) {
    this.subscriptions = subscriptions;
  }

  /** Returns the durable opt-in state for a player. */
  public boolean subscribed(UUID id) {
    return subscriptions.contains(id);
  }

  /**
   * Persists a changed preference before altering the online cursor. Enabling an already online
   * subscriber keeps their existing schedule; disabling removes their cursor.
   */
  public void set(UUID id, boolean enabled, long tick) throws IOException {
    if (subscribed(id) != enabled) subscriptions.set(id, enabled);
    if (enabled)
      online.computeIfAbsent(id, ignored -> new ReportSchedule(tick, interval, cooldown));
    else online.remove(id);
  }

  /**
   * Returns or creates a subscriber’s online cursor, or null when unsubscribed. Repeated queries do
   * not postpone the next briefing.
   */
  public ReportSchedule schedule(UUID id, long tick) {
    if (!subscribed(id)) {
      online.remove(id);
      return null;
    }
    return online.computeIfAbsent(id, ignored -> new ReportSchedule(tick, interval, cooldown));
  }

  /** Removes a player’s online cursor without unsubscribing them. */
  public void forget(UUID id) {
    online.remove(id);
  }

  /** Removes cursors for players absent from the current online set. */
  public void retain(Set<UUID> present) {
    online.keySet().retainAll(present);
  }
}
