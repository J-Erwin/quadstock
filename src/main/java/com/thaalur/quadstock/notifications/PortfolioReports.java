package com.thaalur.quadstock.notifications;

import com.thaalur.quadstock.data.LivingMarket;
import com.thaalur.quadstock.data.MarketCalendar;
import com.thaalur.quadstock.trading.HoldingsLedger;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main-thread chat delivery for opted-in players across all configured tickers.
 *
 * <p>Reports use committed holdings and mark prices, not executable sale quotes. Preferences are
 * durable; delivery cursors are session-only. Pending events from all held industries share one
 * digest cooldown per player; per-ticker cursors prevent simultaneous stories from suppressing each
 * other.
 */
public final class PortfolioReports {
  private final JavaPlugin plugin;
  private final MarketCalendar calendar;
  private final ReportSessions sessions;
  private final HoldingsLedger ledger;
  private final java.util.Map<String, LivingMarket> markets;
  private boolean enabled = true;
  private int headlineLimit = 4;

  /**
   * Sets the global delivery switch, resetting online schedules. Intervals are market ticks;
   * configure on the server thread before scheduling delivery.
   */
  public void configure(boolean enabled, long interval, long cooldown, int headlineLimit) {
    if (headlineLimit < 1 || headlineLimit > 18)
      throw new IllegalArgumentException("News limit must be 1–18");
    this.headlineLimit = headlineLimit;
    this.enabled = enabled;
    sessions.configure(interval, cooldown);
  }

  /** Creates report delivery from the configured ticker registry and shared holdings ledger. */
  public PortfolioReports(
      JavaPlugin plugin,
      MarketCalendar calendar,
      java.util.Map<String, LivingMarket> markets,
      Subscriptions subscriptions,
      HoldingsLedger ledger) {
    this.plugin = plugin;
    this.calendar = calendar;
    this.sessions = new ReportSessions(subscriptions);
    this.ledger = ledger;
    this.markets = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(markets));
  }

  /**
   * Returns the persisted opt-in preference, independent of the administrator’s delivery switch.
   */
  public boolean subscribed(UUID player) {
    return sessions.subscribed(player);
  }

  /**
   * Returns a player-facing subscription/due-time message. May lazily create the online delivery
   * cursor.
   */
  public String status(UUID player) {
    if (!enabled)
      return "Automatic reports are paused by the administrator; subscription preferences are"
          + " saved.";
    ReportSchedule schedule = sessions.schedule(player, calendar.ticks());
    if (schedule == null) return "Chat briefings: off.";
    long remaining = Math.max(0, schedule.nextBriefingTick() - calendar.ticks());
    return "Next briefing: "
        + MarketCalendar.format(schedule.nextBriefingTick())
        + " ("
        + (remaining * 60 + MarketCalendar.HOUR_TICKS - 1) / MarketCalendar.HOUR_TICKS
        + " market minutes remaining).";
  }

  /**
   * Toggles the durable preference and sends a result to the player; failed writes leave it
   * unchanged.
   */
  public void toggle(Player player) {
    set(player, !subscribed(player.getUniqueId()));
  }

  /**
   * Persists the requested preference before changing session delivery state. Repeated subscription
   * preserves an existing schedule; re-subscription after removal starts a new interval.
   */
  public void set(Player player, boolean enabled) {
    if (enabled
        && !com.thaalur.quadstock.security.Permissions.require(
            player, com.thaalur.quadstock.security.Permissions.REPORTS)) return;
    try {
      sessions.set(player.getUniqueId(), enabled, calendar.ticks());
      player.sendMessage(
          Component.text(
              enabled
                  ? "[QuadStock] Subscribed: periodic private briefings and industry alerts for"
                      + " your actual portfolio. /quadstock unsubscribe to stop."
                  : "[QuadStock] Unsubscribed from automatic briefings and alerts.",
              NamedTextColor.AQUA));
      if (enabled)
        player.sendMessage(
            Component.text("[QuadStock] " + status(player.getUniqueId()), NamedTextColor.AQUA));
      plugin
          .getLogger()
          .info(
              "Chat preference player="
                  + player.getUniqueId()
                  + " enabled="
                  + enabled
                  + " tick="
                  + calendar.ticks()
                  + " | "
                  + status(player.getUniqueId()));
    } catch (IOException e) {
      plugin.getLogger().log(Level.SEVERE, "Cannot persist chat subscription", e);
      player.sendMessage(
          Component.text(
              "Could not save your preference. Your subscription has not changed.",
              NamedTextColor.RED));
    }
  }

  /** Drops only the online cursor on logout; the player remains subscribed for a later login. */
  public void forget(UUID player) {
    sessions.forget(player);
  }

  /** Called on the main thread once per second; only online, opted-in players are considered. */
  public void tick() {
    if (!enabled) return;
    long now = calendar.ticks();
    var present = new HashSet<UUID>();
    for (Player player : plugin.getServer().getOnlinePlayers()) {
      if (!player.hasPermission(com.thaalur.quadstock.security.Permissions.USE)
          || !player.hasPermission(com.thaalur.quadstock.security.Permissions.REPORTS)) continue;
      UUID id = player.getUniqueId();
      present.add(id);
      var schedule = sessions.schedule(id, now);
      if (schedule == null) continue;
      var news = schedule.news();
      for (var entry : markets.entrySet()) {
        boolean held = ledger.position(id, entry.getKey()).shares() > 0;
        news.discover(
            entry.getKey(),
            entry.getValue().events(),
            held && entry.getValue().eventsEnabled(),
            now);
      }
      boolean briefing = schedule.briefingDue(now), newsDue = schedule.newsDue(now);
      if (!briefing && !newsDue) continue;
      Component digest =
          Component.text(
              "[QuadStock · "
                  + (briefing ? "Portfolio" : "Industry news")
                  + "] "
                  + MarketCalendar.format(now),
              NamedTextColor.AQUA);
      if (briefing) {
        double value = 0, profit = 0;
        var positions = new java.util.ArrayList<String>();
        for (var entry : markets.entrySet()) {
          var holding = ledger.position(id, entry.getKey());
          double mark = holding.shares() * entry.getValue().currentPrice();
          double unrealized = mark - holding.cost().doubleValue();
          value += mark;
          profit += unrealized + holding.realized().doubleValue();
          if (holding.shares() > 0)
            positions.add(
                String.format(
                    Locale.US,
                    "%s: %d shares · value $%,.2f · unrealized $%+,.2f",
                    entry.getKey(),
                    holding.shares(),
                    mark,
                    unrealized));
        }
        digest =
            digest.append(
                Component.text(
                    String.format(
                        Locale.US,
                        "\nValue $%,.2f · cumulative investment profit $%+,.2f",
                        value,
                        profit)));
        for (String position : positions) digest = digest.append(Component.text("\n" + position));
        if (positions.isEmpty()) digest = digest.append(Component.text("\nNo current holdings."));
        digest = digest.append(link(" [Portfolio]", "/qm portfolio"));
      }
      var batch = newsDue ? news.batch(headlineLimit) : java.util.List.<PortfolioNews.Item>of();
      long omissions = newsDue ? news.omittedCount() : 0;
      for (var item : batch) {
        var event = item.event();
        var color =
            event.direction().equals("neutral")
                ? NamedTextColor.GRAY
                : event.direction().equals("positive") ? NamedTextColor.GREEN : NamedTextColor.RED;
        digest =
            digest
                .append(
                    Component.text(
                        "\n["
                            + item.ticker()
                            + " · "
                            + event.label()
                            + "] "
                            + event.title()
                            + " — "
                            + event.description()
                            + " ("
                            + MarketCalendar.format(event.startTick())
                            + "; "
                            + (now >= event.endTick() ? "ended " : "ends ")
                            + MarketCalendar.format(event.endTick())
                            + ")",
                        color))
                .append(link(" [Report]", "/qm reports " + item.ticker()));
      }
      int remaining = news.pendingCount() - batch.size();
      if (newsDue && remaining > 0)
        digest =
            digest
                .append(Component.text("\n" + remaining + " more news items queued."))
                .append(link(" [Reports]", "/qm reports " + news.batch(18).getFirst().ticker()));
      if (omissions > 0)
        digest =
            digest
                .append(
                    Component.text(
                        "\n"
                            + omissions
                            + " older news items exceeded the session queue; inspect industry"
                            + " reports."))
                .append(link(" [Market]", "/qm market"));
      if (!batch.isEmpty())
        digest =
            digest.append(
                Component.text(
                    "\nFictional business outlook; actual returns may differ.",
                    NamedTextColor.GRAY));
      player.sendMessage(digest);
      if (briefing) schedule.briefingSent(now);
      if (newsDue) {
        news.delivered(batch, omissions);
        schedule.newsSent(now);
      }
    }

    sessions.retain(present);
  }

  private static Component link(String label, String command) {
    return Component.text(label, NamedTextColor.YELLOW).clickEvent(ClickEvent.runCommand(command));
  }
}
