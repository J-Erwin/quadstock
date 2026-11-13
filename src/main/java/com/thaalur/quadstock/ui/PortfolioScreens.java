package com.thaalur.quadstock.ui;

import com.thaalur.quadstock.data.MarketCalendar;
import com.thaalur.quadstock.data.TimeRange;
import com.thaalur.quadstock.trading.CurrencyFormat;
import com.thaalur.quadstock.trading.HoldingsLedger;
import com.thaalur.quadstock.trading.PortfolioSnapshot;
import com.thaalur.quadstock.ui.MarketMenus.Menu;
import java.math.BigDecimal;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Combined portfolio, ticker position and receipt views. Values come from committed journal
 * entries.
 */
final class PortfolioScreens {
  private final MarketMenus menus;

  PortfolioScreens(MarketMenus menus) {
    this.menus = menus;
  }

  /**
   * Opens combined committed holdings across peer tickers; zero-share positions are hidden while
   * their realized profit remains in the totals.
   */
  public void openPortfolio(Player player) {
    menus.show(player, () -> renderPortfolio(player));
  }

  private Menu renderPortfolio(Player player) {
    Menu menu = menus.menu("QuadStock · Portfolio");
    var snapshot =
        PortfolioSnapshot.capture(
            menus.allMenus.values().stream()
                .map(
                    peer ->
                        new PortfolioSnapshot.Source(
                            peer.ticker,
                            peer.industryName,
                            peer.industryIcon.name(),
                            peer.desk.position(player.getUniqueId()),
                            peer.market.currentPrice()))
                .toList());
    int slot = 9;
    for (var holding : snapshot.holdings()) {
      var peer = menus.allMenus.get(holding.ticker());
      menu.button(
          slot++,
          menus.item(
              peer.industryIcon,
              peer.ticker + " · " + holding.shares() + " shares",
              NamedTextColor.AQUA,
              List.of(
                  line(peer.marketName),
                  valueLine("Value: ", holding.value(), holding.unrealized(), false),
                  line("Cost basis: " + TabPortfolioRenderer.money(holding.cost())),
                  valueLine(
                      "Unrealized profit: ", holding.unrealized(), holding.unrealized(), true),
                  line("Click for position details and performance."))),
          peer::openPosition);
    }
    menu.put(
        4,
        menus.item(
            Material.CHEST,
            "Combined portfolio",
            NamedTextColor.GOLD,
            List.of(
                valueLine("Holdings value: ", snapshot.value(), snapshot.unrealized(), false),
                line("Remaining cost: " + TabPortfolioRenderer.money(snapshot.cost())),
                valueLine(
                    "Unrealized profit: ", snapshot.unrealized(), snapshot.unrealized(), true),
                valueLine("Realized profit: ", snapshot.realized(), snapshot.realized(), true))));
    if (slot == 9)
      menu.put(
          22,
          menus.item(
              Material.PAPER,
              "No current holdings",
              NamedTextColor.GRAY,
              "Realized profit and receipts remain available."));
    menu.button(
        29,
        menus.item(
            Material.SPYGLASS,
            "Browse industry performance",
            NamedTextColor.AQUA,
            "Select an industry, then its performance chart."),
        menus::openHome);
    menu.button(
        31,
        menus.item(
            Material.BOOK,
            "Transaction history",
            NamedTextColor.AQUA,
            "All your industry receipts."),
        this::openTransactions);
    menus.footer(menu, false);
    return menu;
  }

  void openPosition(Player player) {
    menus.show(player, () -> renderPosition(player));
  }

  private Menu renderPosition(Player player) {
    var position = menus.desk.position(player.getUniqueId());
    BigDecimal value =
        BigDecimal.valueOf(menus.market.currentPrice())
            .multiply(BigDecimal.valueOf(position.shares()));
    BigDecimal cost = position.cost();
    BigDecimal gain = value.subtract(cost);
    Menu menu = menus.menu(menus.ticker + " · Position");
    menu.put(
        4,
        menus.item(
            Material.CHEST,
            "Your portfolio",
            NamedTextColor.GOLD,
            "Only committed transactions create holdings.",
            "New accounts start empty; confirmed trades create holdings."));
    menu.put(
        20,
        menus.item(
            Material.GOLD_INGOT,
            "Estimated value · " + TabPortfolioRenderer.money(value),
            TabPortfolioRenderer.color(gain),
            List.of(
                line(position.shares() + (" shares × current " + menus.ticker + " mark")),
                line("Remaining cost basis: " + TabPortfolioRenderer.money(cost)),
                valueLine("Unrealized gain / loss: ", gain, gain, true),
                valueLine("Realized gain / loss: ", position.realized(), position.realized(), true),
                line("Not an executable sale quote."))));
    if (position.shares() > 0) {
      menu.button(
          22,
          menus.item(
              menus.industryIcon,
              (menus.ticker + " · ") + position.shares() + " shares",
              NamedTextColor.AQUA,
              "Average cost: " + menus.money(cost.doubleValue() / position.shares()),
              "Click for industry history."),
          p -> menus.openTicker(p, TimeRange.WEEK));
    } else {
      menu.put(
          22,
          menus.item(
              Material.PAPER,
              "No current holdings",
              NamedTextColor.GRAY,
              "You currently own no shares.",
              "Your transaction history and realized profit remain available."));
    }
    menu.button(
        24,
        menus.item(
            Material.BOOK,
            "Transaction history",
            NamedTextColor.AQUA,
            "Confirmed receipts and pending operations.",
            "The former sample portfolio was never real ownership."),
        this::openTransactions);
    if (menus.ledger.blocked())
      menu.put(
          31,
          menus.item(
              Material.BARRIER,
              "Settlement requires attention",
              NamedTextColor.RED,
              "An operation is pending or the journal is unavailable.",
              "Uncertain payments are never automatically repeated."));
    menu.button(
        29,
        menus.item(
            Material.SPYGLASS,
            "Portfolio performance",
            NamedTextColor.AQUA,
            "Value and profit over market hours, days, weeks and months.",
            "Purchases add value without being counted as profit."),
        p -> menus.openPerformance(p, TimeRange.DAY, true));
    menus.footer(menu, true);
    return menu;
  }

  private static Component line(String text) {
    return Component.text(text, NamedTextColor.GRAY);
  }

  private static Component valueLine(
      String label, BigDecimal amount, BigDecimal gain, boolean signed) {
    return line(label)
        .append(
            Component.text(
                signed ? TabPortfolioRenderer.signed(amount) : TabPortfolioRenderer.money(amount),
                TabPortfolioRenderer.color(gain)));
  }

  /**
   * Opens the latest seven operations for this player across all tickers, including nonterminal
   * entries.
   */
  public void openTransactions(Player player) {
    menus.show(player, () -> renderTransactions(player));
  }

  private Menu renderTransactions(Player player) {
    Menu menu = menus.menu("QuadStock · Transactions");
    var history = menus.ledger.history(player.getUniqueId());
    menu.put(
        4,
        menus.item(
            Material.BOOK,
            "Your transaction journal",
            NamedTextColor.GOLD,
            "Latest 7 operations · Total: " + history.size(),
            "Only COMMITTED entries are completed trade receipts.",
            "Pending entries do not create portfolio holdings."));
    if (history.isEmpty())
      menu.put(
          22,
          menus.item(
              Material.PAPER,
              "No transactions yet",
              NamedTextColor.GRAY,
              "Your wallet has not been charged by QuadStock.",
              "Purchase previews never create a trade."));
    for (int i = 0; i < Math.min(7, history.size()); i++) {
      var entry = history.get(history.size() - 1 - i);
      var intent = entry.intent();
      menu.put(
          19 + i,
          menus.item(
              Material.PAPER,
              intent.side() + " " + intent.ticker() + " · " + entry.state(),
              NamedTextColor.AQUA,
              "ID: " + intent.id(),
              "Shares: " + intent.shares() + " · Amount: " + CurrencyFormat.exact(intent.amount()),
              "Policy: " + intent.mode(),
              "Provider: " + intent.provider(),
              "Opened: " + MarketCalendar.format(intent.tick()),
              "Latest step: " + MarketCalendar.format(entry.steps().getLast().tick()),
              entry.state() == HoldingsLedger.State.IN_FLIGHT
                  ? "Payment outcome uncertain: requires reconciliation."
                  : "Status is from the durable journal."));
    }
    menus.footer(menu, false);
    return menu;
  }
}
