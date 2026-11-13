package com.thaalur.quadstock.ui;

import com.thaalur.quadstock.data.ChartWindow;
import com.thaalur.quadstock.data.LivingMarket;
import com.thaalur.quadstock.data.MarketCalendar;
import com.thaalur.quadstock.data.PriceBar;
import com.thaalur.quadstock.data.TimeRange;
import com.thaalur.quadstock.trading.CurrencyFormat;
import com.thaalur.quadstock.trading.HoldingsLedger;
import com.thaalur.quadstock.trading.TradingDesk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Paper inventory controller for one ticker, with navigation to peer tickers.
 *
 * <p>Includes executable trade confirmation as well as read-only charts and portfolio views. All
 * methods and callbacks run on the server thread. Rendering must not pay the economy provider; only
 * an explicit confirmation delegates to TradingDesk.
 *
 * <p>Each inventory carries its controller identity to prevent peer listeners from executing one
 * click multiple times. Refresh preserves page/range state and fixed quote terms. Focused screen
 * renderers share this controller’s navigation and inventory lifecycle.
 */
public final class MarketMenus implements Listener {
  final JavaPlugin plugin;
  final MarketCalendar clock;
  final LivingMarket market;
  final Predicate<UUID> subscribed;
  final Consumer<Player> toggleSubscription;
  final Supplier<String> commerceStatus;
  final Function<UUID, String> reportStatus;
  final HoldingsLedger ledger;
  final TradingDesk desk;
  final String ticker, industryName, marketName;
  final Material industryIcon;
  final Set<String> linkedItems;
  Map<String, MarketMenus> allMenus = new java.util.LinkedHashMap<>();
  int maxOrder = 10000;
  long refreshNanos = 2_000_000_000L;

  final BukkitTask refreshTask;

  /**
   * Binds this ticker’s models and callbacks and schedules main-thread refresh polling. The owner
   * must register the controller and call closeAll during shutdown.
   */
  public MarketMenus(
      JavaPlugin plugin,
      MarketCalendar clock,
      LivingMarket market,
      Predicate<UUID> subscribed,
      Consumer<Player> toggleSubscription,
      Supplier<String> commerceStatus,
      Function<UUID, String> reportStatus,
      HoldingsLedger ledger,
      TradingDesk desk,
      com.thaalur.quadstock.config.MarketSettings settings,
      com.thaalur.quadstock.config.MarketSettings.Industry industry,
      Map<String, MarketMenus> menus) {
    maxOrder = settings.maxShares();
    ticker = industry.ticker();
    industryName = industry.name();
    marketName = settings.markets().get(industry.market());
    industryIcon = Material.valueOf(industry.icon());
    linkedItems = industry.items();
    allMenus = menus;
    refreshNanos = (long) (settings.refreshSeconds() * 1_000_000_000L);
    this.plugin = plugin;
    this.clock = clock;
    this.market = market;
    this.subscribed = subscribed;
    this.toggleSubscription = toggleSubscription;
    this.commerceStatus = commerceStatus;
    this.reportStatus = reportStatus;
    this.ledger = ledger;
    this.desk = desk;
    refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOpenMenus, 1L, 1L);
  }

  final TradeDialogs tradeDialogs = new TradeDialogs(this);

  /** Invalidates dialogs across ticker controllers when opening any inventory view. */
  void cancelDialogs(Player player) {
    for (var peer : allMenus.values()) peer.tradeDialogs.forget(player);
  }

  @EventHandler
  public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
    tradeDialogs.forget(event.getPlayer());
  }

  private final TradingScreens tradingScreens = new TradingScreens(this);

  private final ChartScreens chartScreens = new ChartScreens(this);
  private final PortfolioScreens portfolioScreens = new PortfolioScreens(this);
  private final ReportScreen reportScreen = new ReportScreen(this);

  /** Opens trade selection without moving money. */
  public void openTrade(Player player, HoldingsLedger.Side side, int quantity) {
    if (!com.thaalur.quadstock.security.Permissions.require(
        player, com.thaalur.quadstock.security.Permissions.TRADE)) return;
    tradingScreens.openTrade(player, side, quantity);
  }

  /** Opens a nonbinding purchase estimate. */
  public void openTradePreview(Player player, int shares) {
    tradingScreens.openTradePreview(player, shares);
  }

  /** Opens the latest page with one selected calendar period per column. */
  public void openTicker(Player player, TimeRange range) {
    chartScreens.openTicker(player, range);
  }

  /** Opens a physical display's price chart with its selected history or LIVE mode. */
  public void openDisplayTicker(Player player, TimeRange range, boolean live) {
    if (!com.thaalur.quadstock.security.Permissions.require(
        player, com.thaalur.quadstock.security.Permissions.USE)) return;
    chartScreens.openTicker(player, range, live);
  }

  /** Opens combined committed holdings across industries. */
  public void openPortfolio(Player player) {
    portfolioScreens.openPortfolio(player);
  }

  void openPosition(Player player) {
    portfolioScreens.openPosition(player);
  }

  /** Opens this ticker's value or profit history. */
  public void openPerformance(Player player, TimeRange range, boolean profit) {
    chartScreens.openPerformance(player, range, profit);
  }

  /** Opens recent player receipts across industries. */
  public void openTransactions(Player player) {
    portfolioScreens.openTransactions(player);
  }

  /** Opens this industry's conditions and subscription controls. */
  public void openReports(Player player) {
    if (!com.thaalur.quadstock.security.Permissions.require(
        player, com.thaalur.quadstock.security.Permissions.REPORTS)) return;
    reportScreen.openReports(player);
  }

  /** Opens the report industry selector; no industry is implicitly selected. */
  public void openReportIndustries(Player player) {
    if (!com.thaalur.quadstock.security.Permissions.require(
        player, com.thaalur.quadstock.security.Permissions.REPORTS)) return;
    show(
        player,
        () -> {
          Menu menu = menu("QuadStock · Report industries");
          int slot = 9;
          for (var peer : allMenus.values()) {
            menu.button(
                slot++,
                item(
                    peer.industryIcon,
                    peer.ticker + " · " + peer.industryName,
                    NamedTextColor.AQUA,
                    peer.marketName,
                    "Read this industry's reports."),
                peer::openReports);
          }
          footer(menu, false);
          menu.button(
              40, item(Material.OAK_DOOR, "Back · Markets", NamedTextColor.AQUA), this::openHome);
          return menu;
        });
  }

  /** Resolves MAX from current funds or committed holdings without creating a quote. */
  public int maximumShares(Player player, HoldingsLedger.Side side) {
    int maximum = desk.maximumShares(player.getUniqueId(), side, market.currentPrice());
    if (maximum == 0)
      throw new IllegalArgumentException("No shares available for " + side + " MAX.");
    return maximum;
  }

  /** Opens the configured industry directory with market names and current marks. */
  public void openHome(Player player) {
    show(player, () -> renderHome(player));
  }

  private Menu renderHome(Player player) {
    Menu menu = menu("QuadStock · Markets");
    List<String> directoryLore = new ArrayList<>();
    directoryLore.add("Select an industry to browse its market and trade.");
    directoryLore.add("Market time: " + MarketCalendar.format(clock.ticks()));
    directoryLore.add("Reserves are shared by industries in each exchange.");
    Set<String> shownMarkets = new java.util.HashSet<>();
    for (var peer : allMenus.values()) {
      String marketId = peer.desk.marketId();
      if (shownMarkets.add(marketId))
        directoryLore.add(
            peer.marketName + " reserve: " + CurrencyFormat.exact(ledger.reserve(marketId)));
    }
    menu.put(
        4,
        item(
            Material.COMPASS,
            "Market directory",
            NamedTextColor.GOLD,
            directoryLore.toArray(String[]::new)));
    int slot = 9;
    for (var peer : allMenus.values()) {
      menu.button(
          slot++,
          item(
              peer.industryIcon,
              peer.ticker + " · " + peer.industryName,
              NamedTextColor.AQUA,
              peer.marketName,
              "Current mark: " + money(peer.market.currentPrice()),
              "Owned: " + peer.desk.position(player.getUniqueId()).shares(),
              "Linked items: " + String.join(", ", Integer.toString(peer.linkedItems.size()))),
          p -> peer.openTicker(p, TimeRange.DAY));
    }
    footer(menu, false);
    return menu;
  }

  /**
   * Builds either nine full calendar periods or nine rolling subdivisions of the last hour. A null
   * firstPeriod follows the latest page; an explicit period preserves browsing position.
   */
  ChartWindow chartLayout(TimeRange range, boolean live, Long firstPeriod) {
    return live
        ? ChartWindow.live(clock.ticks(), TimeRange.HOUR.ticks(), 9)
        : ChartWindow.history(clock.ticks(), range.ticks(), 9, firstPeriod);
  }

  /** Adds bounded Back/Next/Latest navigation without altering the selected column duration. */
  void paging(
      Menu menu,
      TimeRange range,
      boolean live,
      ChartWindow layout,
      long earliestTick,
      Consumer<Long> navigate) {
    if (live) {
      menu.put(
          46,
          item(
              Material.GRAY_DYE,
              "Back · Unavailable in Live",
              NamedTextColor.GRAY,
              "Choose Hour, Day, Week or Month to browse history."));
      menu.put(
          48,
          item(
              Material.GRAY_DYE,
              "Next · Live follows the present",
              NamedTextColor.GRAY,
              "The rolling window updates automatically."));
      return;
    }
    long first = layout.intervals().getFirst().start() / range.ticks();
    long earliest = earliestTick / range.ticks();
    long latest = Math.max(0, clock.ticks() / range.ticks() - 8);
    if (first > earliest) {
      long back = Math.max(earliest, first - 9);
      menu.button(
          46,
          item(
              Material.ARROW,
              "Back · Older periods",
              NamedTextColor.AQUA,
              "Show up to 9 earlier " + range.label() + " periods.",
              "Browsing older data stays on this page during refresh."),
          p -> navigate.accept(back));
    } else
      menu.put(
          46,
          item(
              Material.GRAY_DYE,
              "Back · Oldest available data",
              NamedTextColor.GRAY,
              "No earlier retained observations for this account or market."));
    if (first < latest) {
      long next = Math.min(latest, first + 9);
      menu.button(
          48,
          item(
              Material.ARROW,
              "Next · Newer periods",
              NamedTextColor.AQUA,
              "Show up to 9 newer " + range.label() + " periods.",
              "The newest page resumes following the calendar."),
          p -> navigate.accept(next == latest ? null : next));
    } else
      menu.put(
          48,
          item(
              Material.GRAY_DYE,
              "Next · Latest page",
              NamedTextColor.GRAY,
              "No future observations are available."));
    menu.button(
        52,
        item(
            Material.NETHER_STAR,
            "Latest periods",
            NamedTextColor.GREEN,
            "Return to the current page and follow the calendar."),
        p -> navigate.accept(null));
  }

  /**
   * Opens a renderable view and schedules its next real-time refresh; renderer captures navigation
   * state.
   */
  void show(Player player, Supplier<Menu> renderer) {
    cancelDialogs(player);
    if (!com.thaalur.quadstock.security.Permissions.require(
        player, com.thaalur.quadstock.security.Permissions.USE)) return;
    Menu menu = renderer.get();
    menu.renderer = renderer;
    menu.nextRefresh = System.nanoTime() + refreshNanos;
    player.openInventory(menu.inventory);
  }

  /**
   * Main-thread updates only; monotonic wall time keeps 100 TPS testing from speeding up refreshes.
   */
  private void refreshOpenMenus() {
    long now = System.nanoTime();
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu)
          || menu.owner != this
          || menu.pending) continue;
      if (menu.pulse != null) menu.pulse.run();
      if (menu.renderer == null || now - menu.nextRefresh < 0) continue;
      menu.nextRefresh = now + refreshNanos;
      try {
        Menu updated = menu.renderer.get();
        for (int slot = 0; slot < menu.inventory.getSize(); slot++) {
          ItemStack next = updated.inventory.getItem(slot);
          if (!java.util.Objects.equals(menu.inventory.getItem(slot), next))
            menu.inventory.setItem(slot, next);
        }
        menu.actions.clear();
        menu.actions.putAll(updated.actions);
      } catch (RuntimeException failure) {
        // Disable this view's automatic updates to avoid repeating a failing render every tick.
        menu.renderer = null;
        plugin.getLogger().warning("Menu auto-refresh stopped: " + failure);
      }
    }
  }

  /**
   * Disables the confirm action at time/day expiry while retaining original quote terms.
   * Journal-version and funding validity are rechecked by TradingDesk.confirm, not this indicator.
   */
  void updateQuoteStatus(Menu menu, TradingDesk.Quote quote) {
    long tick = clock.ticks();
    boolean expired =
        tick < quote.created() || tick >= quote.expires() || tick / 24000 != quote.day();
    if (expired) {
      menu.put(
          22,
          item(
              Material.CLOCK,
              "Quote expired",
              NamedTextColor.RED,
              "Cancel and request a new quote. No payment was made by expiry."));
      menu.actions.remove(30);
      menu.put(
          30,
          item(
              Material.GRAY_CONCRETE,
              "Confirmation unavailable",
              NamedTextColor.GRAY,
              "These terms have expired. Request a new quote."));
      menu.pulse = null;
    } else if (System.nanoTime() - menu.nextRefresh >= 0) {
      menu.nextRefresh = System.nanoTime() + refreshNanos;
      long minutes = (long) Math.ceil((quote.expires() - tick) * 60.0 / 1000);
      menu.put(
          22,
          item(
              Material.CLOCK,
              "Quote expiry",
              NamedTextColor.YELLOW,
              "Up to " + minutes + " market minutes remaining.",
              "Also expires at the next market-day boundary.",
              "Quoted price, quantity and total stay fixed."));
    }
  }

  void footer(Menu menu, boolean industrySelected) {
    menu.button(
        45,
        item(Material.COMPASS, "Markets", NamedTextColor.GOLD, "Return to the market overview."),
        this::openHome);
    menu.button(
        47,
        item(
            Material.CHEST,
            "Portfolio",
            NamedTextColor.AQUA,
            "View your actual holdings and transaction history."),
        this::openPortfolio);
    menu.button(
        49,
        item(
            Material.WRITABLE_BOOK,
            "Reports",
            NamedTextColor.YELLOW,
            industrySelected
                ? "Read reports for " + ticker + " · " + industryName + "."
                : "Choose an industry to read its reports."),
        industrySelected ? this::openReports : this::openReportIndustries);
    if (industrySelected)
      menu.button(
          50,
          item(
              Material.GOLD_INGOT,
              "Trading desk",
              NamedTextColor.YELLOW,
              "Review and confirm a buy or sale."),
          p -> openTrade(p, HoldingsLedger.Side.BUY, 1));
    menu.button(
        53,
        item(Material.BARRIER, "Close", NamedTextColor.RED, "Close QuadStock."),
        Player::closeInventory);
  }

  /**
   * Cancels inventory movement and handles owned left-click actions once on the next server tick,
   * only if the player still has the same menu open.
   */
  @EventHandler
  public void onClick(InventoryClickEvent event) {
    if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu) || menu.owner != this)
      return;
    // Cancel every movement mode, including interactions originating in the player's inventory.
    event.setCancelled(true);
    if (!(event.getWhoClicked() instanceof Player player) || event.getClick() != ClickType.LEFT)
      return;
    if (event.getRawSlot() < 0 || event.getRawSlot() >= menu.inventory.getSize()) return;
    Consumer<Player> action = menu.actions.get(event.getRawSlot());
    if (action == null || menu.pending) return;
    menu.pending = true;
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              menu.pending = false;
              if (player.isOnline()
                  && com.thaalur.quadstock.security.Permissions.require(
                      player, com.thaalur.quadstock.security.Permissions.USE)
                  && player.getOpenInventory().getTopInventory().getHolder() == menu)
                action.accept(player);
            });
  }

  /**
   * Cancels dragging while this controller owns the top inventory, preventing menu-item movement.
   */
  @EventHandler
  public void onDrag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof Menu menu && menu.owner == this)
      event.setCancelled(true);
  }

  /** Cancels this controller’s refresh task and closes only inventories it owns. */
  public void closeAll() {
    refreshTask.cancel();
    for (Player player : Bukkit.getOnlinePlayers()) tradeDialogs.forget(player);
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu
          && menu.owner == this) player.closeInventory();
    }
  }

  Menu menu(String title) {
    Menu menu = new Menu(title, this);
    for (int i = 0; i < 54; i++)
      menu.put(i, item(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY));
    return menu;
  }

  static int level(double value, double low, double span) {
    return Math.max(0, Math.min(3, (int) Math.round(3 * (value - low) / span)));
  }

  static String money(double amount) {
    return String.format(Locale.US, "$%,.2f", amount);
  }

  static String signed(double amount) {
    return (amount >= 0 ? "+" : "−") + money(Math.abs(amount));
  }

  static String percent(double amount) {
    return String.format(Locale.US, "%+.2f%%", amount);
  }

  static String change(List<PriceBar> bars) {
    if (bars.isEmpty()) return "No observations";
    if (bars.getFirst().startTick() == bars.getLast().endTick()) return "Need two observations";
    return percent((bars.getLast().close() / bars.getFirst().open() - 1) * 100);
  }

  static ItemStack item(Material material, String name, NamedTextColor color, String... lines) {
    return item(
        material,
        name,
        color,
        java.util.Arrays.stream(lines)
            .map(line -> Component.text(line, NamedTextColor.GRAY))
            .toList());
  }

  /** Preserves explicit value colors while keeping inventory lore non-italic. */
  static ItemStack item(
      Material material, String name, NamedTextColor color, List<? extends Component> lines) {
    ItemStack stack = new ItemStack(material);
    stack.editMeta(
        meta -> {
          meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
          List<Component> lore = new ArrayList<>();
          for (Component line : lines) lore.add(line.decoration(TextDecoration.ITALIC, false));
          meta.lore(lore);
        });
    return stack;
  }

  /**
   * Inventory identity, actions and refresh state for one open view. The owner guard prevents other
   * ticker controllers from responding to the same Bukkit event.
   */
  static final class Menu implements InventoryHolder {
    final MarketMenus owner;
    final Inventory inventory;
    final Map<Integer, Consumer<Player>> actions = new HashMap<>();
    boolean pending;
    Supplier<Menu> renderer;
    Runnable pulse;
    long nextRefresh = System.nanoTime();

    Menu(String title, MarketMenus owner) {
      this.owner = owner;
      inventory =
          Bukkit.createInventory(this, 54, Component.text(title, NamedTextColor.DARK_GREEN));
    }

    @Override
    public Inventory getInventory() {
      return inventory;
    }

    void put(int slot, ItemStack item) {
      inventory.setItem(slot, item);
    }

    void button(int slot, ItemStack item, Consumer<Player> action) {
      put(slot, item);
      actions.put(slot, action);
    }
  }
}
