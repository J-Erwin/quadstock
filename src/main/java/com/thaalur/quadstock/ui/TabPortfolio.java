package com.thaalur.quadstock.ui;

import com.thaalur.quadstock.config.MarketSettings;
import com.thaalur.quadstock.config.TabSettings;
import com.thaalur.quadstock.data.LivingMarket;
import com.thaalur.quadstock.trading.HoldingsLedger;
import com.thaalur.quadstock.trading.PortfolioSnapshot;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Main-thread private Tab presentation; updates cached holdings without querying SQLite or Vault.
 */
public final class TabPortfolio implements Listener, AutoCloseable {
  private final JavaPlugin plugin;
  private final TabSettings settings;
  private final MarketSettings markets;
  private final HoldingsLedger ledger;
  private final Map<String, LivingMarket> simulations;
  private final Map<UUID, TabSession> sessions = new HashMap<>();
  private final ResourcePackInfo pack;
  private final BukkitTask task;
  private final long started = System.nanoTime();
  private long nextRefresh;
  private long revision = -1;
  private boolean closed;

  public TabPortfolio(
      JavaPlugin plugin,
      TabSettings settings,
      MarketSettings markets,
      HoldingsLedger ledger,
      Map<String, LivingMarket> simulations)
      throws IOException {
    this.plugin = plugin;
    this.settings = settings;
    this.markets = markets;
    this.ledger = ledger;
    this.simulations = simulations;
    pack = PortfolioPack.configured(settings).orElse(null);
    if (pack == null) {
      if (settings.packUrl().isEmpty()) {
        plugin.getLogger().info("Tab portfolio uses text; no resource pack is configured.");
      } else {
        plugin
            .getLogger()
            .warning(
                "Tab portfolio uses text: no icon pack is bundled. Set resource-pack.sha1 in"
                    + " tab.yml to the SHA-1 of your administrator-hosted pack to enable icons.");
      }
    }
    for (var industry : markets.industries().values())
      if (pack != null && !settings.iconMaterials().contains(industry.icon()))
        plugin
            .getLogger()
            .warning(
                "Tab icon unavailable for "
                    + industry.ticker()
                    + " ("
                    + industry.icon()
                    + "); using its text label. Add its font to a custom pack and icon-materials.");
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
  }

  private void tick() {
    long now = System.nanoTime();
    long currentRevision = ledger.holdingsRevision();
    if (now < nextRefresh && revision == currentRevision) return;
    nextRefresh = now + (long) (settings.refreshSeconds() * 1_000_000_000L);
    revision = currentRevision;
    for (var player : plugin.getServer().getOnlinePlayers()) refresh(player, now);
  }

  private void refresh(Player player, long now) {
    if (closed || !player.isOnline()) return;
    var session =
        sessions.computeIfAbsent(
            player.getUniqueId(), ignored -> new TabSession(player.playerListFooter()));
    var snapshot =
        PortfolioSnapshot.capture(
            markets.industries().values().stream()
                .map(
                    i ->
                        new PortfolioSnapshot.Source(
                            i.ticker(),
                            i.name(),
                            i.icon(),
                            ledger.position(player.getUniqueId(), i.ticker()),
                            simulations.get(i.ticker()).currentPrice()))
                .toList());
    if (pack != null && !session.packRequested && !snapshot.holdings().isEmpty()) {
      session.packRequested = true;
      player.sendResourcePacks(
          ResourcePackRequest.resourcePackRequest()
              .packs(pack)
              .replace(false)
              .required(false)
              .prompt(Component.text("Show QuadStock industry icons in your Tab portfolio?"))
              .build());
    }
    long page = (now - started) / (settings.pageSeconds() * 1_000_000_000L);
    Component panel =
        TabPortfolioRenderer.render(
            snapshot, session.packLoaded, settings.iconMaterials(), settings.rowsPerPage(), page);
    session.update(player.playerListFooter(), panel).ifPresent(player::sendPlayerListFooter);
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    // Other join handlers may install their static footer first.
    plugin
        .getServer()
        .getScheduler()
        .runTask(plugin, () -> refresh(event.getPlayer(), System.nanoTime()));
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    sessions.remove(event.getPlayer().getUniqueId());
  }

  @EventHandler
  public void onPack(PlayerResourcePackStatusEvent event) {
    if (closed || pack == null || !pack.id().equals(event.getID())) return;
    var session = sessions.get(event.getPlayer().getUniqueId());
    if (session == null || !session.packRequested) return;
    session.packLoaded =
        event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
    refresh(event.getPlayer(), System.nanoTime());
  }

  @Override
  public void close() {
    closed = true;
    task.cancel();
    HandlerList.unregisterAll(this);
    for (var player : plugin.getServer().getOnlinePlayers()) {
      var session = sessions.get(player.getUniqueId());
      if (session == null) continue;
      session.restore(player.playerListFooter()).ifPresent(player::sendPlayerListFooter);
      if (pack != null && session.packRequested) player.removeResourcePacks(pack.id());
    }
    sessions.clear();
  }
}
