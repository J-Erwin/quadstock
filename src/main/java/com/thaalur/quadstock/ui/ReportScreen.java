package com.thaalur.quadstock.ui;

import com.thaalur.quadstock.data.MarketCalendar;
import com.thaalur.quadstock.ui.MarketMenus.Menu;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Industry news, bounded commerce evidence and shared chat subscription controls. */
final class ReportScreen {
  private final MarketMenus menus;

  ReportScreen(MarketMenus menus) {
    this.menus = menus;
  }

  /**
   * Opens this ticker’s event and commerce evidence with the player’s shared subscription control.
   */
  public void openReports(Player player) {
    openReports(player, null);
  }

  private void openReports(Player player, Long throughTick) {
    menus.show(player, () -> renderReports(player, throughTick));
  }

  private Menu renderReports(Player player, Long throughTick) {
    Menu menu = menus.menu((menus.ticker + " · Industry reports"));
    menu.put(
        4,
        menus.item(
            Material.WRITABLE_BOOK,
            menus.industryName + " · Live simulation",
            NamedTextColor.GOLD,
            "Events below drive simulated business conditions.",
            "Market time: " + MarketCalendar.format(menus.clock.ticks()),
            "Actual crops and inventories are unaffected."));
    var events = menus.market.events();
    int last = events.size() - 1;
    while (last >= 0 && throughTick != null && events.get(last).startTick() > throughTick) last--;
    if (last < 0 && !events.isEmpty()) last = Math.min(5, events.size() - 1);
    int pageEnd = last;
    for (int i = 0; i < Math.min(6, pageEnd + 1); i++) {
      var event = events.get(pageEnd - i);
      boolean active = menus.market.activeEvent().map(e -> e.equals(event)).orElse(false);
      menu.put(
          18 + i,
          menus.item(
              event.special() ? Material.NETHER_STAR : Material.PAPER,
              event.label() + " · " + event.title() + (active ? " · Active" : " · Past"),
              event.direction().equals("neutral")
                  ? NamedTextColor.GRAY
                  : event.direction().equals("positive")
                      ? NamedTextColor.GREEN
                      : NamedTextColor.RED,
              event.description(),
              "Started: " + MarketCalendar.format(event.startTick()),
              "Ends: " + MarketCalendar.format(event.endTick()),
              "Outlook: "
                  + String.format(java.util.Locale.US, "%+.1f%%", event.outlookAdjustment() * 100),
              "Movement cap: " + event.maxMovePercent() + "% per market hour.",
              "Fictional outlook; actual price direction may differ."));
    }
    if (events.isEmpty())
      menu.put(
          20,
          menus.item(
              Material.PAPER,
              "Conditions stable",
              NamedTextColor.GRAY,
              "No industry events recorded yet.",
              "The simulation continues between events."));
    menu.put(
        29,
        menus.item(
            Material.CARROT,
            "Local commerce · Outlook",
            NamedTextColor.GRAY,
            "Linked items: " + String.join(", ", Integer.toString(menus.linkedItems.size())),
            menus.commerceStatus.get(),
            menus.market.commerceSummary().display(),
            "Trailing day: " + menus.market.commerceSummary().units() + " capped units",
            "Buyers: "
                + menus.market.commerceSummary().buyers()
                + " / "
                + menus.market.commerceRequirements().buyers()
                + " · Sellers: "
                + menus.market.commerceSummary().sellers()
                + " / "
                + menus.market.commerceRequirements().sellers(),
            "Participants: "
                + menus.market.commerceSummary().participants()
                + " / "
                + menus.market.commerceRequirements().participants(),
            "Pairs: "
                + menus.market.commerceSummary().pairs()
                + " / "
                + menus.market.commerceRequirements().pairs(),
            "Outlook cap +"
                + menus.market.commerceCapPercent()
                + "%; not an instant price increase.",
            "Old evidence fades; virtual shops excluded."));
    if (pageEnd >= 6)
      menu.button(
          38,
          menus.item(
              Material.ARROW,
              "Older news",
              NamedTextColor.YELLOW,
              "Read the previous six recorded events."),
          p -> openReports(p, events.get(pageEnd - 6).startTick()));
    if (throughTick != null)
      menu.button(
          40,
          menus.item(
              Material.COMPASS,
              "Latest news",
              NamedTextColor.AQUA,
              "Follow the latest recorded events."),
          this::openReports);
    if (pageEnd < events.size() - 1)
      menu.button(
          42,
          menus.item(
              Material.SPECTRAL_ARROW,
              "Newer news",
              NamedTextColor.YELLOW,
              "Read the next six recorded events."),
          p -> openReports(p, events.get(Math.min(events.size() - 1, pageEnd + 6)).startTick()));
    boolean enabled = menus.subscribed.test(player.getUniqueId());
    menu.button(
        33,
        menus.item(
            enabled ? Material.BELL : Material.GRAY_DYE,
            "Chat briefings · " + (enabled ? "Subscribed" : "Off"),
            NamedTextColor.AQUA,
            "Private periodic reports for your actual holdings.",
            menus.reportStatus.apply(player.getUniqueId()),
            "Significant events have a notification cooldown.",
            "Click to " + (enabled ? "unsubscribe." : "subscribe.")),
        p -> {
          menus.toggleSubscription.accept(p);
          openReports(p);
        });
    menus.footer(menu, true);
    menu.button(
        40,
        menus.item(Material.OAK_DOOR, "Back · Industries", NamedTextColor.AQUA),
        menus::openReportIndustries);
    return menu;
  }
}
