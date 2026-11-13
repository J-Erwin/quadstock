package com.thaalur.quadstock.ui;

import static org.junit.Assert.*;

import com.thaalur.quadstock.config.TabSettings;
import com.thaalur.quadstock.trading.PortfolioSnapshot;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.Test;

public class TabPortfolioTest {
  private String text(Component c) {
    return (c instanceof TextComponent t ? t.content() : "")
        + c.children().stream().map(this::text).reduce("", String::concat);
  }

  private PortfolioSnapshot sample() {
    var holdings = new ArrayList<PortfolioSnapshot.Holding>();
    for (int i = 0; i < 7; i++)
      holdings.add(
          new PortfolioSnapshot.Holding(
              "I" + i,
              "Industry " + i,
              i == 6 ? "CUSTOM_ITEM" : "WHEAT",
              2,
              new BigDecimal("22"),
              new BigDecimal("20")));
    return new PortfolioSnapshot(
        holdings, new BigDecimal("154"), new BigDecimal("140"), new BigDecimal("999"));
  }

  @Test
  public void pagesRetainCombinedTotalsAndWrap() {
    var first = TabPortfolioRenderer.render(sample(), false, Set.of(), 6, 0);
    var second = TabPortfolioRenderer.render(sample(), false, Set.of(), 6, 1);
    assertTrue(text(first).contains("$154.00"));
    assertTrue(text(second).contains("$154.00"));
    assertTrue(text(first).contains("+$14.00"));
    assertFalse(text(first).contains("999"));
    assertTrue(text(first).contains("Industry 5"));
    assertFalse(text(first).contains("Industry 6"));
    assertTrue(text(second).contains("Industry 6"));
    assertEquals(first, TabPortfolioRenderer.render(sample(), false, Set.of(), 6, 2));
  }

  @Test
  public void iconsRequireLoadedPackAndDeclaredMaterialWithoutChangingTextFont() {
    var plain = TabPortfolioRenderer.render(sample(), false, Set.of("CHEST", "WHEAT"), 6, 0);
    assertFalse(text(plain).contains("\ue000"));
    var loaded = TabPortfolioRenderer.render(sample(), true, Set.of("CHEST", "WHEAT"), 6, 0);
    assertEquals(7, text(loaded).chars().filter(c -> c == '\ue000').count());
    assertEquals(Key.key("minecraft", "default"), loaded.font());
    var custom = TabPortfolioRenderer.render(sample(), true, Set.of("CHEST", "WHEAT"), 6, 1);
    assertEquals(1, text(custom).chars().filter(c -> c == '\ue000').count());
    assertTrue(text(custom).contains("Industry 6"));
  }

  @Test
  public void roundedGainsHaveConsistentColorAndSign() {
    assertEquals(NamedTextColor.GRAY, TabPortfolioRenderer.color(new BigDecimal("-.004")));
    assertEquals("$0.00", TabPortfolioRenderer.signed(new BigDecimal("-.004")));
    assertEquals(NamedTextColor.RED, TabPortfolioRenderer.color(new BigDecimal("-.005")));
    assertEquals("−$0.01", TabPortfolioRenderer.signed(new BigDecimal("-.005")));
    assertEquals(NamedTextColor.GREEN, TabPortfolioRenderer.color(new BigDecimal(".005")));
    assertEquals("+$0.01", TabPortfolioRenderer.signed(new BigDecimal(".005")));
  }

  @Test
  public void emptyHoldingsHidePanelEvenWithRealizedProfit() {
    assertEquals(
        Component.empty(),
        TabPortfolioRenderer.render(
            new PortfolioSnapshot(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN),
            true,
            Set.of("CHEST"),
            6,
            0));
  }

  @Test
  public void footerPreservedDeduplicatedAndRestoredAfterSaleOrDisable() {
    var original = Component.text("Lobby");
    var panel = Component.text("Portfolio");
    var session = new TabSession(original);
    var written = session.update(original, panel).orElseThrow();
    assertEquals("Lobby\nPortfolio", text(written));
    assertTrue(session.update(written, panel).isEmpty());
    assertEquals(original, session.restore(written).orElseThrow());
    assertEquals(original, session.update(written, Component.empty()).orElseThrow());
    assertTrue(session.restore(original).isEmpty());
    assertTrue(session.update(original, panel).isPresent());
  }

  @Test
  public void competingFooterIsNeverOverwrittenOrRestoredOver() {
    var session = new TabSession(null);
    var ours = session.update(null, Component.text("Portfolio")).orElseThrow();
    var theirs = Component.text("Other plugin");
    assertTrue(session.update(theirs, Component.text("New portfolio")).isEmpty());
    assertTrue(session.restore(theirs).isEmpty());
    assertTrue(session.update(ours, Component.text("Retry")).isEmpty());
    assertTrue(new TabSession(null).update(theirs, ours).isEmpty());
  }

  @Test
  public void existingFooterStyleDoesNotLeakIntoPortfolio() {
    var original =
        Component.text("Lobby")
            .font(Key.key("lobby", "custom"))
            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD);
    var panel = TabPortfolioRenderer.render(sample(), false, Set.of(), 6, 0);
    var combined = new TabSession(original).update(original, panel).orElseThrow();
    assertNull(combined.font());
    assertEquals(
        net.kyori.adventure.text.format.TextDecoration.State.NOT_SET,
        combined.decoration(net.kyori.adventure.text.format.TextDecoration.BOLD));
    assertEquals(original, combined.children().getFirst());
    assertEquals(panel, combined.children().getLast());
  }

  @Test
  public void missingPackKeepsLegacyConfigurationAndTextPortfolioUsable() throws Exception {
    var file = Files.createTempFile("tab-legacy", ".yml");
    try {
      String legacy = "resource-pack:\n  url: https://example.com/pack.zip\n  sha1: ''\n";
      Files.writeString(file, legacy);
      var settings = TabSettings.load(file);
      assertTrue(PortfolioPack.configured(settings).isEmpty());
      assertEquals(legacy, Files.readString(file));
      var panel = TabPortfolioRenderer.render(sample(), false, settings.iconMaterials(), 6, 0);
      assertTrue(text(panel).contains("Industry 0"));
      assertTrue(text(panel).contains("$154.00"));
      assertFalse(text(panel).contains("\ue000"));
      assertNull(PortfolioPack.class.getResource("/quadstock-tab.zip"));
    } finally {
      Files.delete(file);
    }
  }

  @Test
  public void administratorPackUsesExplicitHashWithoutBundledArtwork() {
    String hash = "a".repeat(40);
    var settings =
        new TabSettings(true, 1, 6, 8, "https://example.com/pack.zip", hash, Set.of("CHEST"));
    var pack = PortfolioPack.configured(settings).orElseThrow();
    assertEquals(hash, pack.hash());
    assertEquals(settings.packUrl(), pack.uri().toString());
    assertEquals(pack.id(), PortfolioPack.configured(settings).orElseThrow().id());
    assertTrue(
        PortfolioPack.configured(new TabSettings(true, 1, 6, 8, "", hash, Set.of())).isEmpty());
  }
}
