package com.thaalur.quadstock.ui;

import com.thaalur.quadstock.trading.PortfolioSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Compact, paginated private portfolio footer using optional resource-pack bitmap glyphs. */
public final class TabPortfolioRenderer {
  private TabPortfolioRenderer() {}

  public static Component render(
      PortfolioSnapshot snapshot, boolean packLoaded, Set<String> icons, int rows, long page) {
    if (rows < 1) throw new IllegalArgumentException("Rows must be positive");
    if (snapshot.holdings().isEmpty()) return Component.empty();
    int count = snapshot.holdings().size();
    int pages = (count + rows - 1) / rows;
    int start = (int) Math.floorMod(page, pages) * rows;
    int end = Math.min(count, start + rows);
    Component result =
        Component.empty()
            .font(Key.key("minecraft", "default"))
            .append(icon("CHEST", packLoaded, icons))
            .append(Component.text("QUADSTOCK · Portfolio", NamedTextColor.GOLD))
            .append(Component.newline())
            .append(Component.text("Value: ", NamedTextColor.GRAY))
            .append(Component.text(money(snapshot.value()), color(snapshot.unrealized())))
            .append(Component.text("  |  Unrealized: ", NamedTextColor.GRAY))
            .append(Component.text(signed(snapshot.unrealized()), color(snapshot.unrealized())));
    for (var holding : snapshot.holdings().subList(start, end)) {
      var color = color(holding.unrealized());
      result =
          result
              .append(Component.newline())
              .append(icon(holding.icon(), packLoaded, icons))
              .append(
                  Component.text(
                      shortName(holding.name()) + " · " + holding.ticker(), NamedTextColor.AQUA))
              .append(Component.text("  " + holding.shares() + " sh  ", NamedTextColor.GRAY))
              .append(
                  Component.text(
                      money(holding.value()) + "  (" + signed(holding.unrealized()) + ")", color));
    }
    return result
        .append(Component.newline())
        .append(
            Component.text(
                (pages > 1 ? "Industries " + (start + 1) + "–" + end + " of " + count + " · " : "")
                    + "Estimated value · /quadstock portfolio",
                NamedTextColor.DARK_GRAY));
  }

  private static Component icon(String material, boolean loaded, Set<String> icons) {
    if (!loaded || !icons.contains(material)) return Component.empty();
    return Component.text("\ue000", NamedTextColor.WHITE)
        .font(Key.key("quadstock", "icons/" + material.toLowerCase(Locale.ROOT)))
        .append(Component.text(" ").font(Key.key("minecraft", "default")));
  }

  private static String shortName(String name) {
    String clean = name.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ");
    return clean.codePointCount(0, clean.length()) <= 22
        ? clean
        : clean.substring(0, clean.offsetByCodePoints(0, 21)) + "…";
  }

  /** Color is based on displayed cents, so a rounded zero cannot appear as a loss. */
  static NamedTextColor color(BigDecimal gain) {
    return switch (gain.setScale(2, RoundingMode.HALF_UP).signum()) {
      case 1 -> NamedTextColor.GREEN;
      case -1 -> NamedTextColor.RED;
      default -> NamedTextColor.GRAY;
    };
  }

  static String money(BigDecimal amount) {
    return String.format(Locale.US, "$%,.2f", amount.setScale(2, RoundingMode.HALF_UP));
  }

  static String signed(BigDecimal amount) {
    var rounded = amount.setScale(2, RoundingMode.HALF_UP);
    return (rounded.signum() > 0 ? "+" : rounded.signum() < 0 ? "−" : "") + money(rounded.abs());
  }
}
