package com.thaalur.quadstock.runtime;

import com.thaalur.quadstock.config.MarketSettings;
import com.thaalur.quadstock.data.LivingMarket;
import com.thaalur.quadstock.data.MarketCalendar;
import com.thaalur.quadstock.persistence.Database;
import com.thaalur.quadstock.trading.HoldingsLedger;
import com.thaalur.quadstock.trading.TradingDesk;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.bukkit.plugin.java.JavaPlugin;

/** Startup-only branding and observed readiness. Never creates accounts or moves currency. */
public final class StartupDiagnostics {
  private StartupDiagnostics() {}

  public static void banner(JavaPlugin plugin) {
    try (var resource = plugin.getResource("cli-message.txt")) {
      if (resource == null) {
        plugin.getLogger().warning("Startup artwork cli-message.txt is missing from the JAR.");
        return;
      }
      new String(resource.readAllBytes(), StandardCharsets.UTF_8)
          .replace('\u00a0', ' ')
          .lines()
          .filter(line -> !line.isBlank())
          .forEach(plugin.getLogger()::info);
    } catch (IOException failure) {
      plugin.getLogger().warning("Cannot read startup artwork: " + failure.getMessage());
    }
  }

  public static void report(
      JavaPlugin plugin,
      MarketSettings settings,
      MarketCalendar calendar,
      Map<String, LivingMarket> markets,
      HoldingsLedger ledger,
      TradingDesk desk,
      String shopStatus,
      long started) {
    var log = plugin.getLogger();
    log.info(
        "QuadStock "
            + plugin.getPluginMeta().getVersion()
            + " | "
            + plugin.getServer().getName()
            + " "
            + plugin.getServer().getMinecraftVersion()
            + " | Java "
            + System.getProperty("java.version"));
    try (var connection = Database.open(plugin.getDataFolder().toPath());
        var sql = connection.createStatement()) {
      try (var rows = sql.executeQuery("PRAGMA journal_mode")) {
        if (rows.next())
          log.info(
              "Storage: SQLite ready | journal="
                  + rows.getString(1)
                  + " | "
                  + plugin.getDataFolder().toPath().resolve("quadstock.db"));
      }
    } catch (Exception failure) {
      log.warning("Storage health query failed: " + failure.getMessage());
    }
    long samples = markets.values().stream().mapToLong(m -> m.observations().size()).sum();
    long items = settings.industries().values().stream().mapToLong(i -> i.items().size()).sum();
    log.info(
        "Markets: "
            + settings.markets().size()
            + " exchanges | "
            + markets.size()
            + " tickers | "
            + items
            + " mapped items | "
            + samples
            + " retained observations");
    log.info(
        "Calendar: "
            + MarketCalendar.format(calendar.ticks())
            + " | ticks="
            + calendar.ticks()
            + " | independent of world daylight");
    log.info(
        "Journal: "
            + ledger.count()
            + " operations | "
            + (ledger.blocked() ? "BLOCKED: inspect /qm settlement" : "no unresolved payments"));
    try {
      String provider = desk.provider();
      int digits = desk.digits();
      log.info("Economy: " + provider + " | decimal places=" + digits);
      if (desk.policy() != null
          && (!desk.policy().provider().equals(provider) || desk.policy().digits() != digits))
        log.warning(
            "Trading BLOCKED: selected policy does not match current economy provider/precision.");
    } catch (RuntimeException | LinkageError unavailable) {
      log.warning(
          "Economy unavailable: "
              + unavailable.getMessage()
              + ". Market browsing remains available.");
    }
    log.info(
        "Settlement: "
            + (desk.policy() == null
                ? "NOT SELECTED: use console 'quadstock settlement select MODE LIMIT'"
                : desk.policy().mode().name())
            + " | global trading="
            + settings.tradingEnabled()
            + " | enabled tickers="
            + settings.industries().values().stream()
                .filter(MarketSettings.Industry::tradable)
                .count());
    log.info(
        "Commerce: "
            + shopStatus
            + " | evidence enabled="
            + settings.commerceEnabled()
            + " | scale="
            + settings.influenceScale()
            + " max-outlook-percent="
            + settings.maxOutlookPercent()
            + " effective-cap-percent="
            + (2 * settings.commerceInfluenceScale())
            + " requirements="
            + settings.commerceRequirements());
    log.info(
        "Narratives: "
            + settings.stories().size()
            + " stories | news headline limit="
            + settings.newsHeadlineLimit()
            + " | event policy resolved per industry");
    log.info(
        "Permissions: Bukkit nodes registered | LuckPerms="
            + (plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms")
                ? "available"
                : "not installed (optional)"));
    log.info(
        "UI refresh: "
            + settings.refreshSeconds()
            + " real seconds | reports="
            + settings.reportsEnabled()
            + " every "
            + settings.reportTicks() / 1000.0
            + " market hours");
    log.info(
        "Startup completed in "
            + ((System.nanoTime() - started) / 1_000_000)
            + " ms. Use /qm status for runtime state.");
  }
}
