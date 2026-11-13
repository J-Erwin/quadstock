package com.thaalur.quadstock.ui;

import com.thaalur.quadstock.data.MarketCalendar;
import com.thaalur.quadstock.data.PriceBar;
import com.thaalur.quadstock.data.TimeRange;
import com.thaalur.quadstock.trading.HoldingsLedger;
import com.thaalur.quadstock.trading.PortfolioHistory;
import com.thaalur.quadstock.ui.MarketMenus.Menu;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Market and position charts. Range and page are retained across refreshes; missing observations
 * stay empty.
 */
final class ChartScreens {
  private final MarketMenus menus;

  ChartScreens(MarketMenus menus) {
    this.menus = menus;
  }

  /** Opens the latest historical page with one selected calendar period per column. */
  public void openTicker(Player player, TimeRange range) {
    openTicker(player, range, false);
  }

  public void openTicker(Player player, TimeRange range, boolean live) {
    openTicker(player, range, live, null);
  }

  private void openTicker(Player player, TimeRange range, boolean live, Long firstPeriod) {
    menus.show(player, () -> renderTicker(player, range, live, firstPeriod));
  }

  private Menu renderTicker(Player player, TimeRange range, boolean live, Long firstPeriod) {
    long now = menus.clock.ticks();
    var layout = menus.chartLayout(range, live, firstPeriod);
    var bins = menus.market.bars(layout);
    List<PriceBar> bars = bins.stream().flatMap(b -> b.prices().stream()).toList();
    Menu menu =
        menus.menu(
            (menus.ticker + " · ") + (live ? "Live · Current bars" : range.label() + " · History"));
    TimeRange[] ranges = TimeRange.values();
    for (int i = 0; i < ranges.length; i++) {
      TimeRange selected = ranges[i];
      menu.button(
          i,
          menus.item(
              !live && selected == range ? Material.LIME_DYE : Material.GRAY_DYE,
              selected.label() + (!live && selected == range ? " · Selected" : " · View"),
              !live && selected == range ? NamedTextColor.GREEN : NamedTextColor.GRAY,
              "Each column: " + selected.label().toLowerCase(Locale.ROOT),
              "Click to redraw this time range."),
          p -> openTicker(p, selected));
    }
    menu.button(
        5,
        menus.item(
            live ? Material.LIME_DYE : Material.ENDER_EYE,
            "Live · Current bars",
            NamedTextColor.AQUA,
            "Fixed buckets; completed bars keep their observations.",
            "Newest bar is forming; its color can still change."),
        p -> openTicker(p, TimeRange.HOUR, true));
    double low = bars.stream().mapToDouble(PriceBar::low).min().orElse(0);
    double high = bars.stream().mapToDouble(PriceBar::high).max().orElse(0);
    double span = Math.max(high - low, 0.01);
    menu.put(
        4,
        menus.item(
            menus.industryIcon,
            (menus.ticker + " · Observed summary"),
            NamedTextColor.GOLD,
            bars.isEmpty()
                ? "No observations in this window."
                : "First observed: " + menus.money(bars.getFirst().open()),
            bars.isEmpty()
                ? "Waiting for a five-minute observation."
                : "Last observed: " + menus.money(bars.getLast().close()),
            "Observed change: " + menus.change(bars),
            bars.isEmpty()
                ? "High / low: unavailable"
                : "High: " + menus.money(high) + " · Low: " + menus.money(low),
            "Empty intervals are excluded; no prices inferred."));
    menu.put(
        6,
        menus.item(
            Material.CLOCK,
            "Market calendar",
            NamedTextColor.AQUA,
            "Intervals from: " + MarketCalendar.format(bins.getFirst().startTick()),
            "Intervals to: " + MarketCalendar.format(bins.getLast().endTick()),
            "Recorded since: " + MarketCalendar.format(menus.market.firstObservationTick()),
            bars.isEmpty()
                ? "No observed range in this window."
                : "Observed from: " + MarketCalendar.format(bars.getFirst().startTick()),
            bars.isEmpty()
                ? "As of: unavailable"
                : "As of: " + MarketCalendar.format(bars.getLast().endTick()),
            "7 days / week · 28 days / month"));
    menu.put(
        7,
        menus.item(
            Material.SPYGLASS,
            "How to read this chart",
            NamedTextColor.YELLOW,
            "Time runs left → right; price increases upward.",
            "Solid block = last observed interval price.",
            "Colored glass = observed high / low range.",
            "Green = up; red = down; gray = unchanged within bar.",
            "Gray columns have no recorded observation.",
            "Scale fits each view; compare using the numbers."));
    menu.put(
        8,
        menus.item(
            Material.PAPER,
            "Vertical scale · 4 levels",
            NamedTextColor.WHITE,
            bars.isEmpty()
                ? "Scale unavailable"
                : "Top: " + menus.money(high) + " · Bottom: " + menus.money(low),
            live
                ? "9 fixed buckets × 6m40s; newest is unfinished."
                : "Each column: " + range.label() + "; 9 periods per page.",
            "Observed intervals: " + bars.size() + " / 9",
            "One sample every 5 market minutes.",
            menus.market.firstObservationTick() > bins.getFirst().startTick()
                ? "Insufficient history for the full period."
                : "Only recorded samples are displayed.",
            "Auto-refreshes on the configured interval while open."));
    for (int column = 0; column < bins.size(); column++) {
      var bin = bins.get(column);
      if (bin.prices().isEmpty()) {
        for (int row = 0; row < 4; row++) {
          menu.put(
              9 + row * 9 + column,
              menus.item(
                  Material.GRAY_STAINED_GLASS_PANE,
                  "Interval " + (column + 1) + " · No observation",
                  NamedTextColor.GRAY,
                  MarketCalendar.format(bin.startTick()),
                  "→ " + MarketCalendar.format(bin.endTick()),
                  "No recorded price in this interval.",
                  "This is a gap, not a zero price."));
        }
        continue;
      }
      PriceBar bar = bin.prices().orElseThrow();
      boolean rising = bar.close() > bar.open();
      boolean flat = Double.compare(bar.close(), bar.open()) == 0;
      int closeLevel = menus.level(bar.close(), low, span);
      int lowLevel = menus.level(bar.low(), low, span);
      int highLevel = menus.level(bar.high(), low, span);
      for (int row = 0; row < 4; row++) {
        int level = 3 - row;
        boolean close = level == closeLevel;
        boolean wick = level >= lowLevel && level <= highLevel;
        Material material =
            close
                ? (flat
                    ? Material.LIGHT_GRAY_CONCRETE
                    : rising ? Material.LIME_CONCRETE : Material.RED_CONCRETE)
                : wick
                    ? (flat
                        ? Material.LIGHT_GRAY_STAINED_GLASS_PANE
                        : rising
                            ? Material.GREEN_STAINED_GLASS_PANE
                            : Material.RED_STAINED_GLASS_PANE)
                    : Material.BLACK_STAINED_GLASS_PANE;
        menu.put(
            9 + row * 9 + column,
            menus.item(
                material,
                "Interval "
                    + (column + 1)
                    + " · "
                    + (close ? "Close " + menus.money(bar.close()) : "Price history"),
                flat ? NamedTextColor.GRAY : rising ? NamedTextColor.GREEN : NamedTextColor.RED,
                "Interval: " + MarketCalendar.format(bin.startTick()),
                "→ " + MarketCalendar.format(bin.endTick()),
                "First observed: " + MarketCalendar.format(bar.startTick()),
                "Last observed: " + MarketCalendar.format(bar.endTick()),
                "Open: " + menus.money(bar.open()) + " · Close: " + menus.money(bar.close()),
                "High: " + menus.money(bar.high()) + " · Low: " + menus.money(bar.low()),
                "Recorded samples: " + bin.observations(),
                live && bin.endTick() > now
                    ? "FORMING: color may change until this bucket closes."
                    : "Closed interval; shifting does not change its samples.",
                "Simulation prices; not executed trades."));
      }
    }
    menus.footer(menu, true);
    menu.button(
        47,
        menus.item(
            Material.CHEST,
            menus.ticker + " position",
            NamedTextColor.AQUA,
            "Position and performance for this industry."),
        menus::openPosition);
    menus.paging(
        menu,
        range,
        live,
        layout,
        menus.market.firstObservationTick(),
        first -> openTicker(player, range, false, first));
    return menu;
  }

  /**
   * Opens per-ticker historical value or cumulative cash-flow-adjusted profit. The selected range
   * is a column duration; profit=true selects the profit metric.
   */
  public void openPerformance(Player player, TimeRange range, boolean profit) {
    openPerformance(player, range, profit, false);
  }

  private void openPerformance(Player player, TimeRange range, boolean profit, boolean live) {
    openPerformance(player, range, profit, live, null);
  }

  private void openPerformance(
      Player player, TimeRange range, boolean profit, boolean live, Long firstPeriod) {
    menus.show(player, () -> renderPerformance(player, range, profit, live, firstPeriod));
  }

  private Menu renderPerformance(
      Player player, TimeRange range, boolean profit, boolean live, Long firstPeriod) {
    Menu menu =
        menus.menu(
            menus.ticker
                + " · Portfolio · "
                + (live ? "Live" : range.label())
                + " · "
                + (profit ? "Profit" : "Value"));
    var layout = menus.chartLayout(range, live, firstPeriod);
    var history = menus.desk.history(player.getUniqueId());
    var bins = PortfolioHistory.bins(history, menus.market.observations(), layout);
    var points = bins.stream().flatMap(b -> b.points().stream()).toList();
    java.util.function.ToDoubleFunction<PortfolioHistory.Point> metric =
        p -> (profit ? p.profit() : p.value()).doubleValue();
    double low = points.stream().mapToDouble(metric).min().orElse(0);
    double high = points.stream().mapToDouble(metric).max().orElse(0);
    double span = Math.max(high - low, 0.01);
    var ranges = TimeRange.values();
    for (int i = 0; i < ranges.length; i++) {
      var selected = ranges[i];
      menu.button(
          i,
          menus.item(
              !live && selected == range ? Material.LIME_DYE : Material.GRAY_DYE,
              selected.label(),
              NamedTextColor.AQUA,
              "Each column: " + selected.label() + "; preserves the selected metric."),
          p -> openPerformance(p, selected, profit));
    }
    menu.button(
        5,
        menus.item(
            live ? Material.LIME_DYE : Material.ENDER_EYE,
            "Live · Current bars",
            NamedTextColor.AQUA,
            "Fixed 6m40s buckets; preserves value / profit selection."),
        p -> openPerformance(p, TimeRange.HOUR, profit, true));
    menu.put(
        4,
        menus.item(
            Material.CHEST,
            profit ? "Cumulative investment profit" : "Holdings value",
            NamedTextColor.GOLD,
            points.isEmpty()
                ? "No recorded observations after a completed trade."
                : "Last observed: " + menus.signed(metric.applyAsDouble(points.getLast())),
            points.size() < 2
                ? "Need two observations for a change."
                : "Change between observed endpoints: "
                    + menus.signed(
                        metric.applyAsDouble(points.getLast())
                            - metric.applyAsDouble(points.getFirst())),
            profit
                ? "Value + cumulative sale proceeds − purchase spending."
                : "Shares owned at each observation × recorded market price.",
            profit
                ? "Purchases and sale withdrawals are accounted for."
                : "Value changes include purchases and sales; they are not returns.",
            "Server wallet cash is excluded; no percentage return shown."));
    menu.put(
        6,
        menus.item(
            Material.CLOCK,
            "Observed period",
            NamedTextColor.AQUA,
            "Intervals: "
                + MarketCalendar.format(bins.getFirst().start())
                + " → "
                + MarketCalendar.format(bins.getLast().end()),
            points.isEmpty()
                ? "Awaiting observations."
                : "Actual observations: "
                    + MarketCalendar.format(points.getFirst().tick())
                    + " → "
                    + MarketCalendar.format(points.getLast().tick()),
            "As of: " + MarketCalendar.format(menus.clock.ticks()),
            live
                ? "Fixed 6m40s buckets; missing prices remain gaps."
                : "Each column: " + range.label() + "; current period may be incomplete."));
    menu.button(
        7,
        menus.item(
            Material.SPYGLASS,
            profit ? "Show holdings value" : "Show investment profit",
            NamedTextColor.YELLOW,
            "Switch metric without changing the selected time range."),
        p -> openPerformance(p, range, !profit, live, firstPeriod));
    menu.put(
        8,
        menus.item(
            Material.PAPER,
            "Chart scale",
            NamedTextColor.WHITE,
            "Top: " + menus.signed(high) + " · Bottom: " + menus.signed(low),
            live
                ? "9 fixed buckets × 6m40s; newest is unfinished."
                : "9 periods per page; one " + range.label() + " per column.",
            "Solid = last value; glass = observed range.",
            "Vertical scale fits available observations.",
            "Green = up; red = down; gray = unchanged within bar.",
            "Profit is cumulative since your first trade, not reset by this window."));
    for (int col = 0; col < bins.size(); col++) {
      var bin = bins.get(col);
      var samples = bin.points();
      if (samples.isEmpty()) {
        for (int row = 0; row < 4; row++)
          menu.put(
              9 + row * 9 + col,
              menus.item(
                  Material.GRAY_STAINED_GLASS_PANE,
                  "No portfolio observation",
                  NamedTextColor.GRAY,
                  MarketCalendar.format(bin.start()) + " → " + MarketCalendar.format(bin.end()),
                  "No value inferred for this interval."));
        continue;
      }
      var last = samples.getLast();
      double firstValue = metric.applyAsDouble(samples.getFirst()),
          lastValue = metric.applyAsDouble(last);
      double bottom = samples.stream().mapToDouble(metric).min().orElseThrow(),
          top = samples.stream().mapToDouble(metric).max().orElseThrow();
      boolean rising = lastValue > firstValue;
      boolean flat = Double.compare(lastValue, firstValue) == 0;
      for (int row = 0; row < 4; row++) {
        int height = 3 - row;
        boolean close = height == menus.level(lastValue, low, span);
        boolean wick =
            height >= menus.level(bottom, low, span) && height <= menus.level(top, low, span);
        Material material =
            close
                ? (flat
                    ? Material.LIGHT_GRAY_CONCRETE
                    : rising ? Material.LIME_CONCRETE : Material.RED_CONCRETE)
                : wick
                    ? (flat
                        ? Material.LIGHT_GRAY_STAINED_GLASS_PANE
                        : rising
                            ? Material.GREEN_STAINED_GLASS_PANE
                            : Material.RED_STAINED_GLASS_PANE)
                    : Material.BLACK_STAINED_GLASS_PANE;
        menu.put(
            9 + row * 9 + col,
            menus.item(
                material,
                "Interval "
                    + (col + 1)
                    + " · "
                    + (profit ? "Profit " : "Value ")
                    + menus.signed(lastValue),
                flat ? NamedTextColor.GRAY : rising ? NamedTextColor.GREEN : NamedTextColor.RED,
                MarketCalendar.format(bin.start()) + " → " + MarketCalendar.format(bin.end()),
                "Last observed: " + MarketCalendar.format(last.tick()),
                "First: " + menus.signed(firstValue) + " · Last: " + menus.signed(lastValue),
                "High: " + menus.signed(top) + " · Low: " + menus.signed(bottom),
                "Shares: "
                    + last.shares()
                    + " · Holdings value: "
                    + menus.money(last.value().doubleValue()),
                "Cumulative purchases: " + menus.money(last.purchases().doubleValue()),
                "Cumulative sales: " + menus.money(last.sales().doubleValue()),
                "Investment profit: " + menus.signed(last.profit().doubleValue()),
                "Recorded samples: " + samples.size()));
      }
    }
    menus.footer(menu, true);
    long firstTrade =
        history.stream()
            .filter(e -> e.state() == HoldingsLedger.State.COMMITTED)
            .mapToLong(e -> e.steps().getLast().tick())
            .min()
            .orElse(menus.clock.ticks());
    menus.paging(
        menu,
        range,
        live,
        layout,
        Math.max(firstTrade, menus.market.firstObservationTick()),
        first -> openPerformance(player, range, profit, false, first));
    return menu;
  }
}
