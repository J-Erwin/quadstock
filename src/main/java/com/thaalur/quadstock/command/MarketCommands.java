package com.thaalur.quadstock.command;

import com.thaalur.quadstock.commerce.*;
import com.thaalur.quadstock.config.ConfigurationValidator;
import com.thaalur.quadstock.config.MarketSettings;
import com.thaalur.quadstock.data.*;
import com.thaalur.quadstock.notifications.*;
import com.thaalur.quadstock.trading.*;
import com.thaalur.quadstock.ui.MarketMenus;
import java.io.IOException;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Routes player and console commands. Authorization is enforced before side effects. */
public final class MarketCommands implements CommandExecutor, TabCompleter {
  private com.thaalur.quadstock.display.DisplayCommands displays;

  public void displays(com.thaalur.quadstock.display.DisplayCommands commands) {
    displays = commands;
  }

  private final JavaPlugin plugin;
  private final MarketSettings settings;
  private final MarketCalendar calendar;
  private final HoldingsLedger ledger;
  private final PortfolioReports reports;
  private final QuickShopObserver shopObserver;
  private final CommerceObservations commerce;
  private final Map<String, LivingMarket> industries;
  private final Map<String, TradingDesk> desks;
  private final Map<String, MarketMenus> screens;
  private final LivingMarket market;
  private final TradingDesk desk;
  private final MarketMenus menus;

  public MarketCommands(
      JavaPlugin plugin,
      MarketSettings settings,
      MarketCalendar calendar,
      HoldingsLedger ledger,
      PortfolioReports reports,
      QuickShopObserver shopObserver,
      CommerceObservations commerce,
      Map<String, LivingMarket> industries,
      Map<String, TradingDesk> desks,
      Map<String, MarketMenus> screens) {
    this.plugin = plugin;
    this.settings = settings;
    this.calendar = calendar;
    this.ledger = ledger;
    this.reports = reports;
    this.shopObserver = shopObserver;
    this.commerce = commerce;
    this.industries = Map.copyOf(industries);
    this.desks = Map.copyOf(desks);
    this.screens = Map.copyOf(screens);
    market = industries.get(settings.defaultTicker());
    desk = desks.get(settings.defaultTicker());
    menus = screens.get(settings.defaultTicker());
  }

  @Override
  public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
    if (sender instanceof Player
        && !com.thaalur.quadstock.security.Permissions.require(
            sender, com.thaalur.quadstock.security.Permissions.USE)) return true;
    String action = args.length == 0 ? "market" : args[0].toLowerCase(Locale.ROOT);
    if (action.equals("display") && displays != null) return displays.execute(sender, args);
    if (action.equals("reload")) {
      return ReloadCommand.execute(
          sender, args, () -> ((com.thaalur.quadstock.QuadStockPlugin) plugin).reloadMarket());
    }
    if (action.equals("config")) {
      if (!sender.hasPermission("quadstock.admin")) {
        sender.sendMessage("Configuration inspection requires quadstock.admin.");
        return true;
      }
      try {
        if (args.length == 2 && args[1].equals("check")) {
          var candidate =
              MarketSettings.load(plugin.getDataFolder().toPath().resolve("config.yml"));
          ConfigurationValidator.validate(plugin.getDataFolder().toPath(), candidate, ledger);
          sender.sendMessage(
              "Configuration valid: "
                  + candidate.markets().size()
                  + " markets, "
                  + candidate.industries().size()
                  + " industries, "
                  + candidate.stories().size()
                  + " validated stories. Use /qm reload to apply; nothing changed now.");
        } else
          sender.sendMessage(
              "Active: "
                  + settings.markets().size()
                  + " markets, "
                  + settings.industries().size()
                  + " industries; refresh="
                  + settings.refreshSeconds()
                  + " real seconds; history="
                  + settings.historyRetentionMonths()
                  + " market months; trading="
                  + settings.tradingEnabled()
                  + ". /qm config check validates the file without applying.");
      } catch (Exception e) {
        sender.sendMessage("Invalid configuration: " + e.getMessage());
      }
      return true;
    }
    if (action.equals("settlement")) {
      if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
        sender.sendMessage("Settlement administration is available only in the server console.");
        return true;
      }
      try {
        if (args.length == 1) {
          for (var entry : desks.entrySet())
            sender.sendMessage(
                entry.getKey()
                    + " ("
                    + entry.getValue().marketId()
                    + "): "
                    + entry.getValue().status());
          ledger
              .pending()
              .ifPresent(e -> sender.sendMessage("Pending: " + e.intent() + " | " + e.steps()));
        } else if (args[1].equals("select") && args.length == 4) {
          var policy =
              new TradingPolicy(
                  TradePreview.SettlementMode.valueOf(args[2].toUpperCase(Locale.ROOT)),
                  desk.provider(),
                  desk.digits(),
                  new java.math.BigDecimal(args[3]));
          for (var i : settings.industries().values())
            if (i.dailyLimit() != null
                && i.dailyLimit().stripTrailingZeros().scale() > policy.digits())
              throw new IOException(i.ticker() + ": payout limit exceeds currency precision");
          for (var trading : desks.values()) trading.validatePolicy(policy);
          policy.save(plugin.getDataFolder().toPath());
          for (var trading : desks.values()) trading.activate(policy);
          sender.sendMessage(
              "Activated "
                  + desk.status()
                  + "; daily ticker allowance="
                  + policy.dailyLimit()
                  + ". Historical reserves and reserve-mode daily payouts are preserved;"
                  + " unlimited-mode trades do not fund reserves.");
        } else if (args[1].equals("recover")) {
          String outcome = args.length > 2 ? args[2] : "safe";
          String note =
              args.length > 3
                  ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length))
                  : "";
          sender.sendMessage(desk.recover(calendar.ticks(), outcome, note));
        } else
          sender.sendMessage(
              "/qm settlement [select RESERVE_BACKED|UNLIMITED_LIQUIDITY dailyLimit | recover"
                  + " safe|paid|failed evidence-note]");
      } catch (Exception e) {
        sender.sendMessage("Settlement refused: " + e.getMessage());
        plugin.getLogger().warning("Settlement administration: " + e);
      }
      return true;
    }
    if (action.equals("commerce")) {
      if (!sender.hasPermission("quadstock.commerce.inspect")) {
        sender.sendMessage("You do not have permission to inspect commerce diagnostics.");
        return true;
      }
      sender.sendMessage(
          "[QuadStock] " + shopObserver.status() + " | " + market.commerceSummary().display());
      sender.sendMessage(
          "Session only: received="
              + commerce.total()
              + " | candidates="
              + commerce.candidates()
              + " | queue drops="
              + shopObserver.dropped()
              + " | decode errors="
              + shopObserver.errors());
      sender.sendMessage(
          "Demand: SELLING only; capped pair/player units; minimum "
              + settings.commerceRequirements().buyers()
              + " buyers, "
              + settings.commerceRequirements().sellers()
              + " sellers, "
              + settings.commerceRequirements().participants()
              + " participants, "
              + settings.commerceRequirements().pairs()
              + " pairs; outlook cap "
              + market.commerceCapPercent()
              + "%.");
      var recent = commerce.recent();
      for (var record : recent.subList(Math.max(0, recent.size() - 5), recent.size())) {
        var trade = record.trade();
        sender.sendMessage(
            "["
                + MarketCalendar.format(record.marketTick())
                + "] "
                + record.classification()
                + " | shop="
                + trade.shopId()
                + " "
                + trade.shopType()
                + " | "
                + trade.units()
                + " "
                + trade.material()
                + " | buyer="
                + trade.buyer()
                + " seller="
                + trade.seller()
                + " | currency="
                + (trade.currency() == null || trade.currency().isBlank()
                    ? "default"
                    : trade.currency())
                + " | gross="
                + trade.gross()
                + " net="
                + trade.net());
      }
      return true;
    }
    if (action.equals("clock")) {
      sender.sendMessage(
          Component.text(
              "[QuadStock] "
                  + MarketCalendar.format(calendar.ticks())
                  + " | ticks="
                  + calendar.ticks(),
              NamedTextColor.AQUA));
      return true;
    }
    if (action.equals("status")) {
      for (var e : industries.entrySet())
        sender.sendMessage(
            e.getKey()
                + " · "
                + settings.markets().get(settings.industries().get(e.getKey()).market())
                + " · mark="
                + e.getValue().currentPrice()
                + " · "
                + desks.get(e.getKey()).status());
      sender.sendMessage(
          Component.text(
              String.format(
                  Locale.US,
                  "[QuadStock] %s $%.4f | observations=%d | since=%s | events=%d",
                  settings.defaultTicker(),
                  market.currentPrice(),
                  market.observations().size(),
                  MarketCalendar.format(market.firstObservationTick()),
                  market.events().size()),
              NamedTextColor.AQUA));
      sender.sendMessage(market.commerceSummary().display());
      sender.sendMessage(desk.status());
      if (sender instanceof Player player) sender.sendMessage(reports.status(player.getUniqueId()));
      else
        for (Player player : plugin.getServer().getOnlinePlayers())
          sender.sendMessage(player.getName() + ": " + reports.status(player.getUniqueId()));
      return true;
    }
    if (!(sender instanceof Player player)) {
      sender.sendMessage(
          "Join with a Minecraft client and run /quadstock. Use /quadstock clock or status in"
              + " console.");
      return true;
    }
    if (List.of("ticker", "performance", "preview", "reports", "trade").contains(action)
        && (action.equals("trade") ? args.length != 2 && args.length != 4 : args.length != 2)) {
      sender.sendMessage(
          "Usage: /qm "
              + action
              + " TICKER"
              + (action.equals("trade") ? " [buy|sell AMOUNT|MAX]" : ""));
      return true;
    }
    String selected = args.length > 1 ? args[1].toUpperCase(Locale.ROOT) : settings.defaultTicker();
    var view = screens.get(selected);
    if (view == null) {
      sender.sendMessage(
          "Unknown ticker " + selected + ". Available: " + String.join(", ", screens.keySet()));
      return true;
    }
    switch (action) {
      case "market" -> menus.openHome(player);
      case "ticker" -> view.openTicker(player, TimeRange.DAY);
      case "performance" -> view.openPerformance(player, TimeRange.DAY, true);
      case "transactions" -> menus.openTransactions(player);
      case "trade" -> {
        try {
          var side =
              args.length == 4
                  ? HoldingsLedger.Side.valueOf(args[2].toUpperCase(Locale.ROOT))
                  : HoldingsLedger.Side.BUY;
          int quantity =
              args.length == 4
                  ? args[3].equalsIgnoreCase("MAX")
                      ? view.maximumShares(player, side)
                      : com.thaalur.quadstock.trading.ShareQuantity.parse(
                          args[3], settings.maxShares())
                  : 1;
          view.openTrade(player, side, quantity);
        } catch (RuntimeException invalid) {
          player.sendMessage(invalid.getMessage());
          player.sendMessage(
              "Usage: /qm trade TICKER buy|sell AMOUNT|MAX. Choose MAX or a whole-share amount from"
                  + " 1 to "
                  + settings.maxShares()
                  + ". Example: /qm trade "
                  + selected
                  + " buy 25");
        }
      }
      case "preview" -> view.openTradePreview(player, 1);
      case "portfolio", "pf" -> menus.openPortfolio(player);
      case "reports" -> view.openReports(player);
      case "subscribe" -> reports.set(player, true);
      case "unsubscribe" -> reports.set(player, false);
      default ->
          sender.sendMessage(
              Component.text(
                  "Use /quadstock"
                      + " [market|ticker|portfolio|reports|clock|status|subscribe|unsubscribe|preview|transactions|trade|performance|reload].",
                  NamedTextColor.YELLOW));
    }
    return true;
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command cmd, String alias, String[] args) {
    if (args.length > 1 && args[0].equalsIgnoreCase("display") && displays != null)
      return displays.complete(sender, args);
    if (args.length == 3 && args[0].equalsIgnoreCase("trade"))
      return List.of("buy", "sell").stream()
          .filter(v -> v.startsWith(args[2].toLowerCase(Locale.ROOT)))
          .toList();
    if (args.length == 4 && args[0].equalsIgnoreCase("trade"))
      return List.of("1", "10", "100", "MAX").stream()
          .filter(
              v ->
                  (v.equals("MAX") || Integer.parseInt(v) <= settings.maxShares())
                      && v.startsWith(args[3].toUpperCase(Locale.ROOT)))
          .toList();
    return args.length == 1
        ? List.of(
                "display",
                "market",
                "portfolio",
                "reports",
                "clock",
                "status",
                "subscribe",
                "unsubscribe",
                "commerce",
                "preview",
                "transactions",
                "trade",
                "performance",
                "ticker",
                "config",
                "reload")
            .stream()
            .filter(
                s -> !s.equals("commerce") || sender.hasPermission("quadstock.commerce.inspect"))
            .filter(s -> !s.equals("display") || sender.hasPermission("quadstock.display.admin"))
            .filter(s -> !s.equals("reload") || sender.hasPermission("quadstock.admin"))
            .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
            .toList()
        : args.length == 2
                && List.of("ticker", "trade", "preview", "performance", "reports").contains(args[0])
            ? screens.keySet().stream()
                .filter(t -> t.startsWith(args[1].toUpperCase(Locale.ROOT)))
                .toList()
            : List.of();
  }
}
