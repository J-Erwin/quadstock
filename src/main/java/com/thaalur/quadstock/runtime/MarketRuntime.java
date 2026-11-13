package com.thaalur.quadstock.runtime;

import com.thaalur.quadstock.commerce.CommerceObservations;
import com.thaalur.quadstock.commerce.QuickShopObserver;
import com.thaalur.quadstock.config.MarketSettings;
import com.thaalur.quadstock.data.LivingMarket;
import com.thaalur.quadstock.data.MarketCalendar;
import com.thaalur.quadstock.notifications.PortfolioReports;
import com.thaalur.quadstock.notifications.Subscriptions;
import com.thaalur.quadstock.trading.*;
import com.thaalur.quadstock.ui.MarketMenus;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Paper composition root: owns startup, commands, scheduled simulation, and shutdown.
 *
 * <p>All application mutations run on the Paper server thread. QuickShop callbacks are queued and
 * drained there. One calendar, ledger and Vault gateway are shared by all tickers; each ticker has
 * its own simulation, trading desk and menu controller.
 *
 * <p>Startup validates configuration and saved identities before registering commands.
 * Initialization or checkpoint failures disable the plugin rather than resetting data. Command
 * routing is delegated to MarketCommands; this class owns resource lifetime.
 */
public final class MarketRuntime implements Listener, AutoCloseable {
  private final JavaPlugin plugin;

  public MarketRuntime(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  private com.thaalur.quadstock.display.DisplayService displays;
  private MarketCalendar calendar;
  private LivingMarket market;
  private PortfolioReports reports;
  private QuickShopObserver shopObserver;
  private HoldingsLedger ledger;
  private MarketSettings settings;
  private com.thaalur.quadstock.ui.TabPortfolio tabPortfolio;

  /** Only a fully initialized runtime may write shutdown checkpoints. */
  private boolean initialized;

  private final Map<String, LivingMarket> industries = new LinkedHashMap<>();
  private final Map<String, TradingDesk> desks = new LinkedHashMap<>();
  private final Map<String, MarketMenus> screens = new LinkedHashMap<>();

  /**
   * Initializes storage and services, registers commands/listeners, then schedules ticks and
   * checkpoints. Startup failure disables this plugin; partially initialized fields are handled by
   * shutdown.
   */
  public void start() {
    long started = System.nanoTime();
    StartupDiagnostics.banner(plugin);
    try {
      plugin.saveDefaultConfig();
      if (!java.nio.file.Files.exists(plugin.getDataFolder().toPath().resolve("tab.yml")))
        plugin.saveResource("tab.yml", false);
      if (!java.nio.file.Files.exists(plugin.getDataFolder().toPath().resolve("stories.yml")))
        plugin.saveResource("stories.yml", false);
      settings = MarketSettings.load(plugin.getDataFolder().toPath().resolve("config.yml"));
      com.thaalur.quadstock.config.ConfigurationValidator.validate(
          plugin.getDataFolder().toPath(), settings, ledger);
      calendar = MarketCalendar.load(plugin.getDataFolder().toPath());
      ledger = HoldingsLedger.open(plugin.getDataFolder().toPath());
      com.thaalur.quadstock.config.ConfigurationValidator.validate(
          plugin.getDataFolder().toPath(), settings, ledger);
      if (ledger.latestTick() > calendar.ticks())
        calendar.addTicks(ledger.latestTick() - calendar.ticks());
      var initialPolicy = TradingPolicy.load(plugin.getDataFolder().toPath()).orElse(null);
      var gateway = VaultGateway.optional(plugin);
      for (var industry : settings.industries().values()) {
        var simulation =
            LivingMarket.load(
                plugin.getDataFolder().toPath(),
                calendar.ticks(),
                industry,
                settings.commerceInfluenceScale(),
                settings.commerceRequirements(),
                settings.stories(),
                settings.historyRetentionMonths());
        industries.put(industry.ticker(), simulation);
        var trading =
            new TradingDesk(ledger, gateway, initialPolicy, industry.ticker(), industry.market());
        trading.configure(
            industry.ticker(),
            industry.market(),
            industry.dailyLimit(),
            settings.quoteTicks(),
            settings.maxShares(),
            settings.tradingEnabled() && industry.tradable());
        trading.configureShareLimits(industry.shareSupply(), industry.maxSharesPerPlayer());
        desks.put(industry.ticker(), trading);
      }
      market = industries.get(settings.defaultTicker());
      var subscriptions = Subscriptions.load(plugin.getDataFolder().toPath());
      reports = new PortfolioReports(plugin, calendar, industries, subscriptions, ledger);
      reports.configure(
          settings.reportsEnabled(),
          settings.reportTicks(),
          settings.alertCooldownTicks(),
          settings.newsHeadlineLimit());
      var items = new java.util.HashSet<String>();
      settings.industries().values().forEach(i -> items.addAll(i.items()));
      var commerce = new CommerceObservations(items);
      shopObserver =
          new QuickShopObserver(
              plugin,
              commerce,
              trade -> {
                String ticker = settings.tickerFor(trade.material());
                if (settings.commerceEnabled() && ticker != null)
                  industries.get(ticker).recordCommerce(trade);
              });
      shopObserver.start();
      for (var industry : settings.industries().values()) {
        var screen =
            new MarketMenus(
                plugin,
                calendar,
                industries.get(industry.ticker()),
                reports::subscribed,
                reports::toggle,
                shopObserver::status,
                reports::status,
                ledger,
                desks.get(industry.ticker()),
                settings,
                industry,
                screens);
        screens.put(industry.ticker(), screen);
        plugin.getServer().getPluginManager().registerEvents(screen, plugin);
      }
      settings.validateSavedIdentities(plugin.getDataFolder().toPath(), true);
      saveState();
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      var command = Objects.requireNonNull(plugin.getCommand("quadstock"));
      var commands =
          new com.thaalur.quadstock.command.MarketCommands(
              plugin,
              settings,
              calendar,
              ledger,
              reports,
              shopObserver,
              commerce,
              industries,
              desks,
              screens);
      var displayYaml = new org.bukkit.configuration.file.YamlConfiguration();
      displayYaml.load(plugin.getDataFolder().toPath().resolve("config.yml").toFile());
      displays =
          new com.thaalur.quadstock.display.DisplayService(
              plugin,
              com.thaalur.quadstock.display.DisplaySettings.read(
                  displayYaml, com.thaalur.quadstock.display.DisplaySettings::blockMaterial),
              settings,
              industries,
              screens,
              () ->
                  (settings.commerceEnabled() ? "" : "Influence disabled · ")
                      + shopObserver.status());
      displays.start();
      commands.displays(new com.thaalur.quadstock.display.DisplayCommands(displays));
      command.setExecutor(commands);
      command.setTabCompleter(commands);

      var tabSettings =
          com.thaalur.quadstock.config.TabSettings.load(
              plugin.getDataFolder().toPath().resolve("tab.yml"));
      if (tabSettings.enabled())
        tabPortfolio =
            new com.thaalur.quadstock.ui.TabPortfolio(
                plugin, tabSettings, settings, ledger, industries);

      plugin
          .getServer()
          .getScheduler()
          .runTaskTimer(
              plugin,
              () -> {
                calendar.addTicks(1);
                for (var simulation : industries.values()) simulation.advanceTo(calendar.ticks());
                shopObserver.drain(calendar.ticks());
              },
              1L,
              1L);
      plugin.getServer().getScheduler().runTaskTimer(plugin, reports::tick, 20L, 20L);
      // Calendar and all markets share one SQLite checkpoint transaction.
      plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkpoint, 1200L, 1200L);
      initialized = true;
      StartupDiagnostics.report(
          plugin,
          settings,
          calendar,
          industries,
          ledger,
          desks.get(settings.defaultTicker()),
          shopObserver.status(),
          started);
    } catch (Exception e) {
      plugin
          .getLogger()
          .log(Level.SEVERE, "Cannot initialize living market; refusing to reset saved state.", e);
      plugin.getServer().getPluginManager().disablePlugin(plugin);
    }
  }

  /**
   * Commits calendar and market checkpoints in one transaction. An I/O failure disables the plugin.
   */
  private void saveState() throws IOException {
    var snapshots = new LinkedHashMap<String, byte[]>();
    snapshots.put("calendar", calendar.snapshot());
    for (var entry : industries.entrySet())
      snapshots.put("market/" + entry.getKey(), entry.getValue().snapshot());
    com.thaalur.quadstock.persistence.StateStore.writeAll(
        plugin.getDataFolder().toPath(), snapshots);
  }

  private void checkpoint() {
    if (!initialized) return;
    try {
      saveState();
    } catch (IOException e) {
      plugin
          .getLogger()
          .log(
              Level.SEVERE,
              "Cannot save market state. Disabling QuadStock to preserve history.",
              e);
      plugin.getServer().getPluginManager().disablePlugin(plugin);
    }
  }

  /** Whether startup completed and this runtime can accept a reload. */
  public boolean initialized() {
    return initialized;
  }

  /** Read-only validation against current durable identities before touching the active runtime. */
  public void validateReload() throws Exception {
    var directory = plugin.getDataFolder().toPath();
    var candidate = MarketSettings.load(directory.resolve("config.yml"));
    com.thaalur.quadstock.config.ConfigurationValidator.validate(directory, candidate, ledger);
    if (ledger.blocked())
      throw new IOException("Journal requires recovery; resolve it before reloading.");
  }

  /** Flushes queued commerce and durably saves the current runtime before replacing it. */
  public void checkpointReload() throws IOException {
    shopObserver.drain(calendar.ticks());
    saveState();
  }

  /** Closes after the explicit checkpoint; storage-close failures prevent replacement startup. */
  public void closeForReload() throws IOException {
    initialized = false;
    detach();
    if (ledger != null) {
      ledger.close();
      ledger = null;
    }
  }

  private void detach() {
    if (displays != null) displays.close();
    plugin.getServer().getScheduler().cancelTasks(plugin);
    org.bukkit.event.HandlerList.unregisterAll(plugin);
    if (tabPortfolio != null) tabPortfolio.close();
    if (shopObserver != null) shopObserver.close();
    for (var screen : screens.values()) screen.closeAll();
  }

  /**
   * Discards online report cursors and outstanding quotes; durable preferences and holdings remain.
   */
  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    if (reports != null) reports.forget(event.getPlayer().getUniqueId());
    for (var trading : desks.values()) trading.forget(event.getPlayer().getUniqueId());
  }

  /**
   * Cancels tasks, detaches the observer and closes owned menus and ledger. Checkpoints only a
   * fully initialized runtime; failed startup must not save partial state.
   */
  @Override
  public void close() {
    detach();
    if (initialized) {
      initialized = false;
      try {
        saveState();
        plugin
            .getLogger()
            .info(
                "Market saved at ticks="
                    + calendar.ticks()
                    + (market == null
                        ? ""
                        : " | observations="
                            + market.observations().size()
                            + " | price="
                            + market.currentPrice()));
      } catch (IOException e) {
        plugin.getLogger().log(Level.SEVERE, "Calendar shutdown save failed.", e);
      }
    }
    if (ledger != null) {
      try {
        ledger.close();
      } catch (IOException e) {
        plugin.getLogger().log(Level.SEVERE, "Cannot close holdings journal", e);
      }
    }
  }
}
