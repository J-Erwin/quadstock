package com.thaalur.quadstock.ui;

import com.thaalur.quadstock.data.MarketCalendar;
import com.thaalur.quadstock.trading.CurrencyFormat;
import com.thaalur.quadstock.trading.HoldingsLedger;
import com.thaalur.quadstock.trading.TradePreview;
import com.thaalur.quadstock.trading.TradingDesk;
import com.thaalur.quadstock.trading.WalletPreview;
import com.thaalur.quadstock.ui.MarketMenus.Menu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Trade selection, read-only estimates and fixed-term confirmation. Only explicit confirmation
 * settles money.
 */
final class TradingScreens {
  private final MarketMenus menus;

  TradingScreens(MarketMenus menus) {
    this.menus = menus;
  }

  /**
   * Clears any previous quote and opens quantity/direction selection. Opening this view does not
   * pay.
   */
  public void openTrade(Player player, HoldingsLedger.Side side, int quantity) {
    if (!canTrade(player)) return;
    menus.desk.forget(player.getUniqueId());
    menus.show(player, () -> renderTrade(player, side, quantity));
  }

  private Menu renderTrade(Player player, HoldingsLedger.Side side, int quantity) {
    boolean buying = side == HoldingsLedger.Side.BUY;
    var modeColor = buying ? NamedTextColor.GREEN : NamedTextColor.GOLD;
    var nextSide = buying ? HoldingsLedger.Side.SELL : HoldingsLedger.Side.BUY;
    Menu menu = menus.menu(menus.ticker + " · " + side + " · Trading desk");
    menu.put(
        4,
        menus.item(
            menus.industryIcon,
            (menus.ticker + " · ") + side,
            modeColor,
            menus.desk.status(),
            "Current mark: " + menus.money(menus.market.currentPrice()),
            "Owned: " + menus.desk.position(player.getUniqueId()).shares() + " shares",
            supplyText(),
            "Your industry holding cap: " + limitText(menus.desk.maxSharesPerPlayer()),
            "Whole shares · zero fees / spread for this exchange."));
    menu.button(
        13,
        menus.item(
            buying ? Material.EMERALD : Material.GOLD_INGOT,
            "Mode: " + side,
            modeColor,
            buying ? "Buying shares with wallet currency." : "Selling shares from your holdings.",
            "Click to switch to " + nextSide + ".",
            "Selected quantity stays the same."),
        p -> openTrade(p, nextSide, quantity));
    int[] sizes = {1, 10, 100};
    for (int i = 0; i < sizes.length; i++) {
      int size = sizes[i];
      if (size > menus.maxOrder) {
        menu.put(
            20 + i,
            menus.item(
                Material.GRAY_DYE,
                size + " shares · Unavailable",
                NamedTextColor.GRAY,
                "Maximum order size: " + menus.maxOrder));
        continue;
      }
      menu.button(
          20 + i,
          menus.item(
              size == quantity ? Material.LIME_DYE : Material.GRAY_DYE,
              size + " shares",
              NamedTextColor.AQUA,
              size == quantity ? "Selected" : "Select quantity"),
          p -> openTrade(p, side, size));
    }
    maximumButton(menu, 23, player, side, false);
    menu.button(
        25,
        menus.item(
            Material.NAME_TAG,
            "Custom amount",
            NamedTextColor.AQUA,
            "Choose 1–" + menus.maxOrder + " whole shares.",
            "Selected: " + quantity + " · " + side,
            "Enter the amount in a dialog, then review your order."),
        p -> menus.tradeDialogs.open(p, side, quantity));
    menu.button(
        31,
        menus.item(
            Material.PAPER,
            "Request " + side + " quote: " + quantity + " shares",
            modeColor,
            "Review exact amount before confirming.",
            "No money moves when requesting a quote."),
        p -> {
          if (!canTrade(p)) return;
          try {
            openConfirmation(
                p,
                menus.desk.quote(
                    p.getUniqueId(),
                    side,
                    quantity,
                    menus.market.currentPrice(),
                    menus.clock.ticks()));
          } catch (RuntimeException e) {
            p.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
          }
        });
    menus.footer(menu, true);
    return menu;
  }

  /**
   * Displays immutable quoted terms. Only the confirmation callback executes settlement;
   * auto-refresh updates expiry status without requoting or changing quantity/price.
   */
  private void openConfirmation(Player player, TradingDesk.Quote quote) {
    Menu menu = menus.menu("Confirm · " + quote.side() + (" " + menus.ticker));
    menu.put(
        4,
        menus.item(
            Material.PAPER,
            "Review your trade",
            NamedTextColor.GOLD,
            "Requested: " + quote.requested() + " · Offered: " + quote.offered(),
            "Unit: "
                + CurrencyFormat.exact(quote.unit())
                + " · Total: "
                + CurrencyFormat.exact(quote.amount()),
            "Expires: " + MarketCalendar.format(quote.expires()),
            menus.desk.status(),
            quote.offered() != quote.requested()
                ? "Funding is limited: this is a SMALLER offer."
                : "Full requested quantity.",
            "Only the offered quantity will be executed."));
    menu.button(
        30,
        menus.item(
            Material.LIME_CONCRETE,
            "Confirm " + quote.side() + " " + quote.offered(),
            NamedTextColor.GREEN,
            quote.side() == HoldingsLedger.Side.BUY
                ? "Withdraw " + CurrencyFormat.exact(quote.amount()) + " from your wallet."
                : "Deposit " + CurrencyFormat.exact(quote.amount()) + " into your wallet.",
            "This action moves real server currency and shares."),
        p -> {
          if (!canTrade(p)) return;
          try {
            p.sendMessage(
                Component.text(
                    menus.desk.confirm(p.getUniqueId(), quote.id(), menus.clock.ticks()),
                    NamedTextColor.AQUA));
          } catch (Exception e) {
            p.sendMessage(
                Component.text(
                    "Trade requires attention; check receipts before retrying: " + e.getMessage(),
                    NamedTextColor.RED));
            menus.plugin.getLogger().warning("Trade confirmation " + quote.id() + ": " + e);
          }
          menus.openPortfolio(p);
        });
    menu.button(
        32,
        menus.item(Material.BARRIER, "Cancel", NamedTextColor.RED, "No payment will be attempted."),
        p -> {
          menus.desk.forget(p.getUniqueId());
          openTrade(p, quote.side(), quote.requested());
        });
    menu.pulse = () -> menus.updateQuoteStatus(menu, quote);
    menu.pulse.run();
    player.openInventory(menu.inventory);
  }

  /** Opens a read-only wallet estimate. It is not an executable offer or a reservation of funds. */
  public void openTradePreview(Player player, int shares) {
    menus.show(player, () -> renderTradePreview(player, shares));
  }

  private Menu renderTradePreview(Player player, int shares) {
    Menu menu = menus.menu((menus.ticker + " · Purchase preview"));
    var wallet = WalletPreview.read(menus.plugin, player);
    menu.put(
        4,
        menus.item(
            menus.industryIcon,
            (menus.ticker + " · Estimate only"),
            NamedTextColor.GOLD,
            "As of: " + MarketCalendar.format(menus.clock.ticks()),
            "Whole shares; fees and spread are zero in this preview.",
            supplyText(),
            "Your industry holding cap: " + limitText(menus.desk.maxSharesPerPlayer()),
            "Price and wallet update automatically while open."));
    int[] quantities = {1, 10, 100};
    for (int i = 0; i < quantities.length; i++) {
      int quantity = quantities[i];
      menu.button(
          19 + i,
          menus.item(
              quantity == shares ? Material.LIME_DYE : Material.GRAY_DYE,
              quantity + " shares" + (quantity == shares ? " · Selected" : ""),
              NamedTextColor.AQUA,
              "Click to estimate this quantity."),
          p -> openTradePreview(p, quantity));
    }
    maximumButton(menu, 22, player, HoldingsLedger.Side.BUY, true);
    if (wallet.available()) {
      var estimate =
          TradePreview.purchase(
              menus.market.currentPrice(), shares, wallet.digits(), wallet.balance());
      menu.put(
          30,
          menus.item(
              Material.PAPER,
              "Estimated purchase cost",
              NamedTextColor.YELLOW,
              "Quantity: " + shares + " whole shares",
              "Unit price: " + CurrencyFormat.exact(estimate.unitPrice()),
              "Total: " + CurrencyFormat.exact(estimate.total()),
              "Currency provider: " + wallet.provider(),
              "Rounded unit price × shares; " + wallet.digits() + " decimal places."));
      menu.put(
          32,
          menus.item(
              estimate.affordable() ? Material.EMERALD : Material.REDSTONE,
              estimate.affordable() ? "Wallet covers this estimate" : "Insufficient wallet balance",
              estimate.affordable() ? NamedTextColor.GREEN : NamedTextColor.RED,
              "Wallet: " + CurrencyFormat.exact(estimate.wallet()),
              "Shortfall: " + CurrencyFormat.exact(estimate.shortfall()),
              "Affordability is not trade approval. No funds reserved."));
    } else
      menu.put(
          31,
          menus.item(
              Material.BARRIER,
              "Wallet unavailable",
              NamedTextColor.RED,
              wallet.problem(),
              "Market browsing remains available."));
    menu.button(
        40,
        menus.item(
            Material.GOLD_INGOT,
            "Open trading desk",
            NamedTextColor.GOLD,
            "This preview never moves money.",
            menus.desk.status(),
            "Click to request a separately confirmed trade."),
        p -> openTrade(p, HoldingsLedger.Side.BUY, shares));
    menus.footer(menu, true);
    return menu;
  }

  private void maximumButton(
      Menu menu, int slot, Player player, HoldingsLedger.Side side, boolean preview) {
    try {
      int maximum = menus.maximumShares(player, side);
      menu.button(
          slot,
          menus.item(
              Material.HOPPER,
              "MAX",
              NamedTextColor.AQUA,
              "Currently: " + maximum + " shares",
              "Limited to " + menus.maxOrder + " shares per order.",
              "Recalculated when clicked; quote and confirmation still required."),
          p -> {
            try {
              int current = menus.maximumShares(p, side);
              if (preview) openTradePreview(p, current);
              else openTrade(p, side, current);
            } catch (RuntimeException failure) {
              p.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
            }
          });
    } catch (RuntimeException failure) {
      menu.put(
          slot,
          menus.item(
              Material.GRAY_DYE, "MAX · Unavailable", NamedTextColor.GRAY, failure.getMessage()));
    }
  }

  private String supplyText() {
    return menus.desk.shareSupply() == Long.MAX_VALUE
        ? "Industry supply: unlimited"
        : "Industry shares available: "
            + menus.desk.availableShares()
            + " / "
            + menus.desk.shareSupply();
  }

  private static String limitText(long value) {
    return value == Long.MAX_VALUE ? "unlimited" : Long.toString(value);
  }

  private boolean canTrade(Player player) {
    return com.thaalur.quadstock.security.Permissions.require(
            player, com.thaalur.quadstock.security.Permissions.USE)
        && com.thaalur.quadstock.security.Permissions.require(
            player, com.thaalur.quadstock.security.Permissions.TRADE);
  }
}
