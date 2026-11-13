package com.thaalur.quadstock.events;

import static org.junit.Assert.*;

import com.thaalur.quadstock.config.MarketSettings.Industry;
import com.thaalur.quadstock.data.LivingMarket;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

public class EventEngineTest {
  static EventSettings settings(String yaml) throws Exception {
    var y = new YamlConfiguration();
    y.loadFromString(yaml);
    return EventSettings.read(y, EventSettings.defaults());
  }

  static Industry industry(EventSettings settings) {
    return new Industry(
        "TEST",
        "TEST",
        "Test Company",
        "WHEAT",
        Set.of("WHEAT"),
        100,
        0,
        true,
        true,
        null,
        "generic",
        settings);
  }

  @Test
  public void specialChanceAndCooldownArePerEventAndPersisted() throws Exception {
    var policy =
        settings(
            "special:\n"
                + "  chance-percent: 100\n"
                + "  positive-weight: 0\n"
                + "  negative-weight: 1\n"
                + "  cooldown-market-days: 14\n");
    var catalog = StoryCatalog.bundled();
    var engine = new EventEngine("TEST");
    var first = engine.next(6000, "TEST", "Company", "generic", policy, catalog);
    assertEquals("special-negative", first.event().category());
    assertTrue(
        first.event().outlookAdjustment() >= -.4 && first.event().outlookAdjustment() <= -.2);
    var bytes = new ByteArrayOutputStream();
    engine.write(new DataOutputStream(bytes));
    var restored =
        EventEngine.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    var second = engine.next(first.nextTick(), "TEST", "Company", "generic", policy, catalog);
    assertFalse(second.event().special());
    assertEquals(
        second, restored.next(first.nextTick(), "TEST", "Company", "generic", policy, catalog));
    assertTrue(
        engine
            .next(
                first.event().endTick() + 14 * 24000L,
                "TEST",
                "Company",
                "generic",
                policy,
                catalog)
            .event()
            .special());
    var never = settings("special:\n  chance-percent: 0\n");
    for (int i = 0; i < 100; i++)
      assertFalse(
          engine
              .next(i * 1_000_000L, "TEST", "Company", "generic", never, catalog)
              .event()
              .special());
  }

  @Test
  public void zeroDirectionWeightsExcludeCategoriesForCustomTickers() throws Exception {
    var catalog = StoryCatalog.bundled();
    for (boolean special : List.of(false, true)) {
      for (String direction : List.of("positive", "negative")) {
        String weights =
            "positive-weight: "
                + (direction.equals("positive") ? 1 : 0)
                + "\nnegative-weight: "
                + (direction.equals("negative") ? 1 : 0)
                + "\n";
        var policy =
            settings(
                special
                    ? "special:\n  chance-percent: 100\n  cooldown-market-days: 0\n  "
                        + weights.replace("\n", "\n  ")
                    : weights + "neutral-weight: 0\nspecial:\n  enabled: false\n");
        var engine = new EventEngine("CUSTOM");
        long tick = 6000;
        for (int i = 0; i < 30; i++) {
          var selection = engine.next(tick, "CUSTOM", "Custom Company", "generic", policy, catalog);
          assertEquals((special ? "special-" : "") + direction, selection.event().category());
          assertEquals(direction.equals("positive"), selection.event().outlookAdjustment() > 0);
          tick = selection.nextTick();
        }
      }
    }
  }

  @Test
  public void narrativeChoicesDoNotAlterEconomicsAndAvoidRepeatedCategoryStories()
      throws Exception {
    var policy =
        settings(
            "positive-weight: 1\n"
                + "negative-weight: 0\n"
                + "neutral-weight: 0\n"
                + "special:\n"
                + "  enabled: false\n");
    var catalog = StoryCatalog.bundled();
    Path small = Files.createTempFile("stories-small", ".yml");
    Files.writeString(
        small,
        "version: 1\n"
            + "collections:\n"
            + "  generic:\n"
            + "    positive:\n"
            + "      - id: only\n"
            + "        headline: One story\n"
            + "        description: One description\n");
    var oneStory = StoryCatalog.load(small);
    var a = new EventEngine("TEST");
    var b = new EventEngine("TEST");
    long tick = 6000;
    String previous = null;
    for (int i = 0; i < 30; i++) {
      var x = a.next(tick, "TEST", "Company", "generic", policy, catalog);
      var y = b.next(tick, "TEST", "Company", "generic", policy, oneStory);
      assertEquals(x.event().outlookAdjustment(), y.event().outlookAdjustment(), 0);
      assertEquals(x.event().endTick(), y.event().endTick());
      assertEquals(x.nextTick(), y.nextTick());
      assertNotEquals(previous, x.event().storyId());
      previous = x.event().storyId();
      tick = x.nextTick();
    }
  }

  @Test
  public void specialMovementIsGradualBoundedAndFasterInBothDirections() throws Exception {
    for (String direction : List.of("positive", "negative")) {
      String selection =
          "special:\n  chance-percent: 100\n  positive-weight: "
              + (direction.equals("positive") ? 1 : 0)
              + "\n  negative-weight: "
              + (direction.equals("negative") ? 1 : 0)
              + "\n";
      var fast =
          LivingMarket.load(Files.createTempDirectory("fast"), 0, industry(settings(selection)), 0);
      var slow =
          LivingMarket.load(
              Files.createTempDirectory("slow"),
              0,
              industry(
                  settings(
                      selection
                          + "  response-half-life-market-hours: 19\n"
                          + "  max-move-percent-per-market-hour: 0.5\n")),
              0);
      fast.advanceTo(12000);
      slow.advanceTo(12000);
      assertTrue(Math.abs(fast.currentPrice() - 100) > Math.abs(slow.currentPrice() - 100));
      assertEquals(direction.equals("positive"), fast.currentPrice() > 100);
      var points = fast.observations();
      for (int i = 1; i < points.size(); i++) {
        var before = points.get(i - 1);
        var after = points.get(i);
        assertTrue(
            Math.abs(after.price() - before.price())
                <= before.price() * .02 * (after.tick() - before.tick()) / 1000 + 1e-10);
        assertTrue(after.price() >= 1 && after.price() <= 1_000_000);
      }
    }
  }

  @Test
  public void restartRetainsActiveTermsAndStoryDespiteConfigurationEdits() throws Exception {
    Path root = Files.createTempDirectory("event-restart");
    var original = industry(settings("special:\n  chance-percent: 100\n"));
    var market = LivingMarket.load(root, 0, original, 0);
    market.advanceTo(9000);
    market.save();
    var different =
        industry(
            settings(
                "special:\n  chance-percent: 0\n"
                    + "  response-half-life-market-hours: 10\n"
                    + "  max-move-percent-per-market-hour: 1\n"
                    + "  positive-impact-min-percent: 1\n"
                    + "  positive-impact-max-percent: 2\n"));
    Path editedFile = Files.createTempFile("edited-stories", ".yml");
    Files.writeString(
        editedFile,
        Files.readString(Path.of("src/main/resources/stories.yml"))
            .replace("headline: \"", "headline: \"Edited: "));
    var editedCatalog = StoryCatalog.load(editedFile);
    var restored =
        LivingMarket.load(
            root,
            9000,
            different,
            0,
            com.thaalur.quadstock.commerce.CommerceSignal.Requirements.DEFAULT,
            editedCatalog);
    assertEquals(market.events(), restored.events());
    assertEquals(market.observations(), restored.observations());
    market.advanceTo(10000);
    restored.advanceTo(10000);
    assertEquals(market.observations(), restored.observations());
    var exact = LivingMarket.load(root, 9000, original, 0);
    market.advanceTo(1_000_000);
    exact.advanceTo(1_000_000);
    assertEquals(market.events(), exact.events());
    assertEquals(market.observations(), exact.observations());
  }

  @Test
  public void neutralEventsHaveNoOutlookEffectAndDisabledEventsAreSilent() throws Exception {
    var policy =
        settings(
            "positive-weight: 0\n"
                + "negative-weight: 0\n"
                + "neutral-weight: 1\n"
                + "special:\n"
                + "  enabled: false\n");
    var market = LivingMarket.load(Files.createTempDirectory("neutral"), 0, industry(policy), 0);
    market.advanceTo(48000);
    assertEquals(100, market.currentPrice(), 0);
    assertTrue(
        market.events().stream()
            .allMatch(e -> e.category().equals("neutral") && e.outlookAdjustment() == 0));
    var i = industry(policy);
    var disabled =
        new Industry(
            i.ticker(),
            i.market(),
            i.name(),
            i.icon(),
            i.items(),
            100,
            0,
            false,
            true,
            null,
            i.storyCollection(),
            policy);
    var quiet = LivingMarket.load(Files.createTempDirectory("disabled"), 0, disabled, 0);
    quiet.advanceTo(48000);
    assertTrue(quiet.events().isEmpty());
    assertEquals(100, quiet.currentPrice(), 0);
  }

  @Test
  public void expiryReturnsToNormalResponseAndChunkedAdvancementMatches() throws Exception {
    var policy =
        settings(
            "positive-weight: 0\n"
                + "negative-weight: 0\n"
                + "neutral-weight: 1\n"
                + "gap-min-market-hours: 100\n"
                + "gap-max-market-hours: 100\n"
                + "special:\n"
                + "  chance-percent: 100\n"
                + "  positive-weight: 0\n"
                + "  negative-weight: 1\n"
                + "  duration-min-market-hours: 2\n"
                + "  duration-max-market-hours: 2\n");
    var a = LivingMarket.load(Files.createTempDirectory("expiry"), 0, industry(policy), 0);
    var b = LivingMarket.load(Files.createTempDirectory("chunked"), 0, industry(policy), 0);
    a.advanceTo(8000);
    double low = a.currentPrice();
    assertTrue(a.activeEvent().isEmpty());
    a.advanceTo(12000);
    for (long t = 0; t <= 12000; t += 37) b.advanceTo(t);
    b.advanceTo(12000);
    assertTrue(a.currentPrice() > low);
    assertEquals(a.observations(), b.observations());
    assertEquals(a.events(), b.events());
  }

  @Test
  public void repeatMemorySurvivesRestartInTheSameCategory() throws Exception {
    var policy =
        settings(
            "positive-weight: 1\n"
                + "negative-weight: 0\n"
                + "neutral-weight: 0\n"
                + "special:\n"
                + "  enabled: false\n");
    var catalog = StoryCatalog.bundled();
    var engine = new EventEngine("TEST");
    var first = engine.next(6000, "TEST", "Company", "generic", policy, catalog);
    var bytes = new ByteArrayOutputStream();
    engine.write(new DataOutputStream(bytes));
    var restored =
        EventEngine.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    var next = restored.next(first.nextTick(), "TEST", "Company", "generic", policy, catalog);
    assertNotEquals(first.event().storyId(), next.event().storyId());
    assertEquals(
        next, engine.next(first.nextTick(), "TEST", "Company", "generic", policy, catalog));
  }
}
