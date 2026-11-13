package com.thaalur.quadstock.display;

import com.thaalur.quadstock.commerce.CommerceSignal;
import com.thaalur.quadstock.data.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;

/** Read-only presentation shared by signs and floating displays; never requests settlement. */
public final class DisplayRenderer {
  private DisplayRenderer() {}

  public record Snapshot(
      String name,
      double price,
      long tick,
      List<LivingMarket.HistoryBin> bins,
      LivingMarket.IndustryEvent news,
      String direction,
      CommerceSignal.Summary commerce,
      String commerceStatus) {
    public Snapshot {
      bins = List.copyOf(bins);
    }
  }

  public static Snapshot snapshot(
      String name, LivingMarket m, DisplayDefinition.Mode mode, String commerceStatus) {
    var news = m.events();
    return new Snapshot(
        name,
        m.currentPrice(),
        m.currentTick(),
        m.bars(mode.window(m.currentTick())),
        news.isEmpty() ? null : news.getLast(),
        m.activeEvent().map(LivingMarket.IndustryEvent::direction).orElse("none"),
        m.commerceSummary(),
        commerceStatus);
  }

  public static OptionalDouble change(List<LivingMarket.HistoryBin> bins) {
    var bars = bins.stream().flatMap(b -> b.prices().stream()).toList();
    if (bars.isEmpty() || bars.getFirst().startTick() == bars.getLast().endTick())
      return OptionalDouble.empty();
    return OptionalDouble.of((bars.getLast().close() / bars.getFirst().open() - 1) * 100);
  }

  public static NamedTextColor movement(double value, int decimals) {
    double rounded =
        java.math.BigDecimal.valueOf(value)
            .setScale(decimals, java.math.RoundingMode.HALF_UP)
            .doubleValue();
    return rounded > 0
        ? NamedTextColor.GREEN
        : rounded < 0 ? NamedTextColor.RED : NamedTextColor.GRAY;
  }

  private static String signed(double value) {
    return String.format(Locale.US, "%+.2f%%", Math.abs(value) < 0.005 ? 0 : value);
  }

  public static String money(double value, boolean compact) {
    if (compact && Math.abs(value) >= 1e6)
      return String.format(
          Locale.US,
          "$%.2f%s",
          value / (Math.abs(value) >= 1e9 ? 1e9 : 1e6),
          Math.abs(value) >= 1e9 ? "B" : "M");
    if (compact && Math.abs(value) >= 1000) return String.format(Locale.US, "$%.1fK", value / 1000);
    return String.format(Locale.US, "$%,.2f", value);
  }

  public static List<Component> render(
      DisplayDefinition d, Snapshot s, int width, long headlineAge) {
    boolean sign = d.surface() == DisplayDefinition.Surface.SIGN;
    var accent = TextColor.fromHexString(d.options().get("accent"));
    var lines = new ArrayList<Component>();
    String mode =
        d.mode() == DisplayDefinition.Mode.LIVE ? "LIVE" : d.mode().name().substring(0, 1);
    var change = change(s.bins());
    var color = change.isPresent() ? movement(change.getAsDouble(), 2) : NamedTextColor.GRAY;
    String delta = change.isPresent() ? signed(change.getAsDouble()) : "No history";
    if (sign && change.isPresent() && Math.abs(change.getAsDouble()) >= 1000) {
      double v = change.getAsDouble();
      double divisor = Math.abs(v) >= 1e6 ? 1e6 : 1000;
      delta = String.format(Locale.US, "%+.1f%s%%", v / divisor, divisor == 1e6 ? "M" : "K");
    }
    String heading = sign ? d.ticker() : d.ticker() + " · " + s.name();
    if (sign
        && d.type() != DisplayDefinition.Type.NEWS
        && d.type() != DisplayDefinition.Type.ACTIVITY)
      heading =
          DisplayText.fit(d.ticker(), width - DisplayText.width(" " + mode, true), true)
              + " "
              + mode;
    lines.add(line(heading, width, accent, true, false));
    switch (d.type()) {
      case SUMMARY -> {
        lines.add(line(money(s.price(), sign), width, NamedTextColor.WHITE, true, false));
        lines.add(line((sign ? "" : "Observed change: ") + delta, width, color, false, false));
        lines.add(
            line(
                sign
                    ? switch (s.direction()) {
                      case "positive" -> "Event +";
                      case "negative" -> "Event -";
                      case "neutral" -> "Neutral";
                      default -> "No event";
                    }
                    : "Event: " + s.direction(),
                width,
                direction(s.direction()),
                false,
                true));
      }
      case PRICE -> {
        lines.add(line(money(s.price(), sign), width, NamedTextColor.WHITE, true, false));
        lines.add(line((sign ? "" : "Observed change: ") + delta, width, color, false, false));
        var bars = s.bins().stream().flatMap(b -> b.prices().stream()).toList();
        double amount = bars.isEmpty() ? 0 : bars.getLast().close() - bars.getFirst().open();
        if (Math.abs(amount) < 0.005) amount = 0;
        lines.add(
            line(
                change.isEmpty()
                    ? "Awaiting samples"
                    : (amount < 0 ? "−" : "+") + money(Math.abs(amount), sign),
                width,
                color,
                false,
                false));
      }
      case CHART -> {
        lines.add(spark(s.bins(), width < 81));
        lines.add(line((sign ? "" : "Observed change: ") + delta, width, color, false, false));
        var bars = s.bins().stream().flatMap(b -> b.prices().stream()).toList();
        lines.add(
            line(
                bars.isEmpty()
                    ? "No observations"
                    : (sign ? "" : "Last observed: ") + money(bars.getLast().close(), sign),
                width,
                NamedTextColor.WHITE,
                false,
                false));
        if (!sign && !bars.isEmpty())
          lines.add(
              line(
                  "Low "
                      + money(bars.stream().mapToDouble(PriceBar::low).min().orElse(0), false)
                      + " · High "
                      + money(bars.stream().mapToDouble(PriceBar::high).max().orElse(0), false),
                  width,
                  NamedTextColor.GRAY,
                  false,
                  false));
      }
      case NEWS -> {
        var n = s.news();
        lines.add(
            line(
                n == null
                    ? "News"
                    : sign
                        ? "M"
                            + (n.startTick() / MarketCalendar.MONTH_TICKS + 1)
                            + " D"
                            + (n.startTick() % MarketCalendar.MONTH_TICKS / MarketCalendar.DAY_TICKS
                                + 1)
                        : MarketCalendar.format(n.startTick()),
                width,
                NamedTextColor.GRAY,
                false,
                true));
        lines.add(
            Component.text(
                n == null
                    ? "No news yet"
                    : DisplayText.scroll(
                        n.title(),
                        width,
                        headlineAge,
                        (long) d.number("scroll-ms"),
                        (long) d.number("pause-ms")),
                n == null ? NamedTextColor.GRAY : direction(n.direction())));
        lines.add(line("Click: reports", width, accent, false, true));
      }
      case ACTIVITY -> {
        var c = s.commerce();
        boolean disabled = s.commerceStatus().startsWith("Influence disabled");
        boolean unavailable = !s.commerceStatus().contains("connected");
        lines.add(
            line(
                disabled
                    ? "Disabled"
                    : unavailable
                        ? "Unavailable"
                        : c.learning()
                            ? "Learning"
                            : c.qualified() ? "Broad activity" : "Low evidence",
                width,
                NamedTextColor.GRAY,
                false,
                true));
        lines.add(
            line(
                (sign && c.units() >= 1000
                        ? String.format(Locale.US, "%.0fK", c.units() / 1000.0)
                        : Integer.toString(c.units()))
                    + (sign ? " u/d" : " capped units / market day"),
                width,
                NamedTextColor.WHITE,
                false,
                false));
        lines.add(
            line(
                (sign ? "Eff " : "Applied outlook ")
                    + String.format(Locale.US, "%+.3f%%", c.adjustment() * 100),
                width,
                movement(c.adjustment() * 100, 3),
                false,
                false));
        if (!sign) {
          lines.add(
              line(
                  c.buyers() + " buyers · " + c.sellers() + " sellers · " + c.pairs() + " pairs",
                  width,
                  NamedTextColor.GRAY,
                  false,
                  false));
          lines.add(line(s.commerceStatus(), width, NamedTextColor.GRAY, false, true));
        }
      }
    }
    if (!sign) {
      if (d.type() != DisplayDefinition.Type.NEWS && d.type() != DisplayDefinition.Type.ACTIVITY)
        lines.add(
            line(
                d.mode() == DisplayDefinition.Mode.LIVE
                    ? "LIVE · current market hour"
                    : "9 market " + d.mode().name().toLowerCase(Locale.ROOT) + " periods",
                width,
                NamedTextColor.GRAY,
                false,
                true));
      lines.add(
          line(
              "As of " + MarketCalendar.format(s.tick()), width, NamedTextColor.GRAY, false, true));
    }
    return List.copyOf(lines);
  }

  private static Component line(String s, int width, TextColor c, boolean bold, boolean italic) {
    return Component.text(DisplayText.fit(s, width, bold), c)
        .decoration(TextDecoration.BOLD, bold)
        .decoration(TextDecoration.ITALIC, italic);
  }

  private static NamedTextColor direction(String d) {
    return d.equals("positive")
        ? NamedTextColor.GREEN
        : d.equals("negative") ? NamedTextColor.RED : NamedTextColor.GRAY;
  }

  public static Component spark(List<LivingMarket.HistoryBin> bins) {
    return spark(bins, false);
  }

  public static Component spark(List<LivingMarket.HistoryBin> bins, boolean narrow) {
    var bars = bins.stream().flatMap(b -> b.prices().stream()).toList();
    double low = bars.stream().mapToDouble(PriceBar::close).min().orElse(0),
        high = bars.stream().mapToDouble(PriceBar::close).max().orElse(low);
    Component result = Component.empty();
    String levels = narrow ? "_-=^" : "▁▂▃▄▅▆▇█";
    for (var bin : bins) {
      if (bin.prices().isEmpty()) {
        result = result.append(Component.text(narrow ? "?" : "·", NamedTextColor.GRAY));
        continue;
      }
      var b = bin.prices().get();
      int level =
          high == low
              ? (levels.length() - 1) / 2
              : (int) Math.round((b.close() - low) / (high - low) * (levels.length() - 1));
      result =
          result.append(
              Component.text(
                  String.valueOf(levels.charAt(Math.clamp(level, 0, levels.length() - 1))),
                  movement(b.close() - b.open(), 2)));
    }
    return result;
  }
}
