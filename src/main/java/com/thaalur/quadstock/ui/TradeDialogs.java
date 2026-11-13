package com.thaalur.quadstock.ui;

import com.thaalur.quadstock.data.MarketCalendar;
import com.thaalur.quadstock.security.Permissions;
import com.thaalur.quadstock.trading.CurrencyFormat;
import com.thaalur.quadstock.trading.HoldingsLedger.Side;
import com.thaalur.quadstock.trading.ShareQuantity;
import com.thaalur.quadstock.trading.TradingDesk;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/** Native amount entry and fixed-quote review. Only the explicit Confirm callback can pay. */
final class TradeDialogs {
  private final MarketMenus menus;
  private final DialogSessions sessions = new DialogSessions();

  TradeDialogs(MarketMenus menus) {
    this.menus = menus;
  }

  void open(Player player, Side side, int quantity) {
    if (!allowed(player)) return;
    menus.cancelDialogs(player);
    player.closeInventory();
    amount(player, side, Integer.toString(quantity), quantity, null);
  }

  /** Invalid input keeps its text and shows an error in the same dialog flow. */
  private void amount(Player player, Side side, String input, int returnQuantity, String error) {
    menus.desk.forget(player.getUniqueId());
    UUID token = sessions.begin(player.getUniqueId());
    var body = new ArrayList<DialogBody>();
    body.add(
        DialogBody.plainMessage(
            Component.text(
                "Enter 1–"
                    + menus.maxOrder
                    + " whole shares. Review the exact quote before confirming.")));
    body.add(
        DialogBody.plainMessage(
            Component.text(
                "Owned: " + menus.desk.position(player.getUniqueId()).shares() + " shares")));
    if (error != null) body.add(DialogBody.plainMessage(Component.text(error, NamedTextColor.RED)));
    var review =
        button(
            player,
            token,
            "Review order",
            text -> {
              try {
                int quantity = ShareQuantity.parse(text, menus.maxOrder);
                var quote =
                    menus.desk.quote(
                        player.getUniqueId(),
                        side,
                        quantity,
                        menus.market.currentPrice(),
                        menus.clock.ticks());
                confirmation(player, quote);
              } catch (IllegalArgumentException | IllegalStateException failure) {
                amount(player, side, bounded(text), returnQuantity, failure.getMessage());
              }
            });
    var cancel =
        button(player, token, "Cancel", text -> menus.openTrade(player, side, returnQuantity));
    player.showDialog(
        Dialog.create(
            builder ->
                builder
                    .empty()
                    .base(
                        DialogBase.builder(
                                Component.text(menus.ticker + " · " + side + " · Custom amount"))
                            .canCloseWithEscape(true)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(body)
                            .inputs(
                                List.of(
                                    DialogInput.text("shares", Component.text("Shares"))
                                        .initial(bounded(input))
                                        .maxLength(10)
                                        .width(220)
                                        .build()))
                            .build())
                    .type(DialogType.confirmation(review, cancel))));
  }

  /** Review uses a fixed quote; Back discards it and retains the originally requested quantity. */
  private void confirmation(Player player, TradingDesk.Quote quote) {
    UUID token = sessions.begin(player.getUniqueId());
    String terms =
        "Requested: "
            + quote.requested()
            + " shares\nOffered: "
            + quote.offered()
            + " shares\nUnit price: "
            + CurrencyFormat.exact(quote.unit())
            + (quote.side() == Side.BUY ? "\nWithdrawal total: " : "\nDeposit total: ")
            + CurrencyFormat.exact(quote.amount())
            + "\nExpires: "
            + MarketCalendar.format(quote.expires())
            + " (or next market day)";
    String funding =
        quote.offered() != quote.requested()
            ? "Funding is limited. Confirm accepts ONLY the smaller offered quantity."
            : "Full requested quantity. No money moves until you confirm.";
    var confirm =
        button(
            player,
            token,
            "Confirm " + quote.side() + " " + quote.offered(),
            ignored -> {
              try {
                player.sendMessage(
                    Component.text(
                        menus.desk.confirm(player.getUniqueId(), quote.id(), menus.clock.ticks()),
                        NamedTextColor.AQUA));
              } catch (Exception failure) {
                player.sendMessage(
                    Component.text(
                        "Trade requires attention; check receipts before retrying: "
                            + failure.getMessage(),
                        NamedTextColor.RED));
                menus
                    .plugin
                    .getLogger()
                    .warning("Dialog trade confirmation " + quote.id() + ": " + failure);
              }
              menus.openPortfolio(player);
            });
    var back =
        button(
            player,
            token,
            "Back — edit amount",
            ignored ->
                amount(
                    player,
                    quote.side(),
                    Integer.toString(quote.requested()),
                    quote.requested(),
                    null));
    player.showDialog(
        Dialog.create(
            builder ->
                builder
                    .empty()
                    .base(
                        DialogBase.builder(
                                Component.text(menus.ticker + " · Review " + quote.side()))
                            .canCloseWithEscape(true)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(
                                List.of(
                                    DialogBody.plainMessage(Component.text(terms)),
                                    DialogBody.plainMessage(
                                        Component.text(funding, NamedTextColor.YELLOW))))
                            .build())
                    .type(DialogType.confirmation(confirm, back))));
  }

  /** Copy client input, then serialize validation and all economy work on the server thread. */
  private ActionButton button(Player owner, UUID token, String label, Consumer<String> action) {
    return ActionButton.create(
        Component.text(label),
        null,
        150,
        DialogAction.customClick(
            (response, audience) -> {
              if (audience != owner || !menus.plugin.isEnabled()) return;
              String text = response.getText("shares");
              menus
                  .plugin
                  .getServer()
                  .getScheduler()
                  .runTask(
                      menus.plugin,
                      () -> {
                        if (!owner.isOnline() || !sessions.consume(owner.getUniqueId(), token))
                          return;
                        owner.closeDialog();
                        if (allowed(owner)) action.accept(text);
                        else menus.desk.forget(owner.getUniqueId());
                      });
            },
            ClickCallback.Options.builder()
                .uses(ClickCallback.UNLIMITED_USES)
                .lifetime(Duration.ofMinutes(10))
                .build()));
  }

  /** Called on navigation, logout and shutdown to invalidate callbacks from old forms. */
  void forget(Player player) {
    if (sessions.forget(player.getUniqueId())) {
      menus.desk.forget(player.getUniqueId());
      player.closeDialog();
    }
  }

  private boolean allowed(Player player) {
    return Permissions.require(player, Permissions.USE)
        && Permissions.require(player, Permissions.TRADE);
  }

  private static String bounded(String text) {
    return text == null ? "" : text.substring(0, Math.min(10, text.length()));
  }
}
