package com.thaalur.quadstock.config;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Material;
import org.junit.Test;

public class MarketSettingsTest {
  private String defaults() throws IOException {
    return Files.readString(Path.of("src/main/resources/config.yml"));
  }

  private MarketSettings parse(String yaml) throws Exception {
    Path directory = Files.createTempDirectory("settings-");
    Path path = directory.resolve("config.yml");
    Files.copy(Path.of("src/main/resources/stories.yml"), directory.resolve("stories.yml"));
    try {
      Files.writeString(path, yaml);
      return MarketSettings.load(
          path,
          item -> {
            try {
              return Material.valueOf(item) != Material.AIR;
            } catch (IllegalArgumentException e) {
              return false;
            }
          });
    } finally {
      Files.deleteIfExists(path);
      Files.deleteIfExists(directory.resolve("stories.yml"));
      Files.deleteIfExists(directory);
    }
  }

  private String replaceRequired(String yaml, String oldValue, String newValue) {
    assertTrue("Missing expected configuration fragment: " + oldValue, yaml.contains(oldValue));
    assertNotEquals("Replacement must change the configuration", oldValue, newValue);
    return yaml.replace(oldValue, newValue);
  }

  @Test
  public void shareLimitsAreOptionalIndependentAndStrictWholeNumbers() throws Exception {
    var original = customMarkets();
    assertEquals(Long.MAX_VALUE, parse(original).industries().get("ORE").shareSupply());
    assertEquals(Long.MAX_VALUE, parse(original).industries().get("ORE").maxSharesPerPlayer());
    var configured =
        parse(
            original.replace(
                "name: Cloud Mining",
                "name: Cloud Mining\n    share-supply: 5000000000\n    max-shares-per-player: 25"));
    assertEquals(5000000000L, configured.industries().get("ORE").shareSupply());
    assertEquals(25, configured.industries().get("ORE").maxSharesPerPlayer());
    assertEquals(Long.MAX_VALUE, configured.industries().get("CRYSTAL").shareSupply());
    for (String key : new String[] {"share-supply", "max-shares-per-player"}) {
      for (String valid : new String[] {"unlimited", "0", "9223372036854775807"})
        parse(
            original.replace(
                "name: Cloud Mining", "name: Cloud Mining\n    " + key + ": " + valid));
      for (String invalid :
          new String[] {"-1", "1.5", "true", "'20'", "9223372036854775808", "[]"}) {
        var error =
            assertThrows(
                IOException.class,
                () ->
                    parse(
                        original.replace(
                            "name: Cloud Mining",
                            "name: Cloud Mining\n    " + key + ": " + invalid)));
        assertTrue(error.getMessage(), error.getMessage().contains("ORE." + key));
      }
    }
  }

  @Test
  public void packagedDefaultsLoadSuccessfully() throws Exception {
    MarketSettings settings = parse(defaults());
    assertNotNull(settings);
    assertEquals(16, settings.industries().size());
    assertEquals("OVERWORLD", settings.industries().get("AGRI").market());
  }

  @Test
  public void packagedIndustriesUseExpectedSharePricesAndVolatility() throws Exception {
    String yaml = defaults();
    MarketSettings settings = parse(yaml);
    Map<String, Integer> prices = new LinkedHashMap<>();
    Map<String, Double> volatility = new LinkedHashMap<>();
    prices.put("AGRI", 35);
    volatility.put("AGRI", 0.65);
    prices.put("MINE", 175);
    volatility.put("MINE", 1.10);
    prices.put("TIMB", 45);
    volatility.put("TIMB", 0.60);
    prices.put("FISH", 65);
    volatility.put("FISH", 1.15);
    prices.put("LIVE", 90);
    volatility.put("LIVE", 0.75);
    prices.put("FOOD", 55);
    volatility.put("FOOD", 0.55);
    prices.put("FLOR", 75);
    volatility.put("FLOR", 1.30);
    prices.put("GEMS", 1250);
    volatility.put("GEMS", 2.50);
    prices.put("STON", 25);
    volatility.put("STON", 0.50);
    prices.put("META", 325);
    volatility.put("META", 1.20);
    prices.put("WOOD", 60);
    volatility.put("WOOD", 0.65);
    prices.put("TEXT", 85);
    volatility.put("TEXT", 0.85);
    prices.put("MOBS", 225);
    volatility.put("MOBS", 1.80);
    prices.put("REDT", 750);
    volatility.put("REDT", 1.65);
    prices.put("BUIL", 150);
    volatility.put("BUIL", 0.80);
    prices.put("EXPL", 2000);
    volatility.put("EXPL", 2.30);

    assertEquals(prices.keySet(), settings.industries().keySet());
    for (String ticker : prices.keySet()) {
      MarketSettings.Industry industry = settings.industries().get(ticker);
      assertNotNull("Missing industry: " + ticker, industry);
      assertEquals(
          ticker + " reference price",
          prices.get(ticker).doubleValue(),
          industry.referencePrice(),
          0.000001);
      assertEquals(
          ticker + " volatility",
          volatility.get(ticker).doubleValue(),
          industry.volatility(),
          0.000001);
    }
  }

  @Test
  public void packagedIndustriesRouteTheirItemsExactlyOnce() throws Exception {
    MarketSettings settings = parse(defaults());
    assertEquals(16, settings.industries().size());

    Map<String, String> mappings =
        Map.ofEntries(
            Map.entry("WHEAT", "AGRI"),
            Map.entry("RAW_IRON", "MINE"),
            Map.entry("OAK_LOG", "TIMB"),
            Map.entry("COD", "FISH"),
            Map.entry("LEATHER", "LIVE"),
            Map.entry("BREAD", "FOOD"),
            Map.entry("POPPY", "FLOR"),
            Map.entry("CACTUS_FLOWER", "FLOR"),
            Map.entry("DIAMOND", "GEMS"),
            Map.entry("STONE", "STON"),
            Map.entry("IRON_INGOT", "META"),
            Map.entry("OAK_PLANKS", "WOOD"),
            Map.entry("WHITE_WOOL", "TEXT"),
            Map.entry("BONE", "MOBS"),
            Map.entry("REDSTONE", "REDT"),
            Map.entry("GLASS", "BUIL"),
            Map.entry("HEART_OF_THE_SEA", "EXPL"));

    for (Map.Entry<String, String> entry : mappings.entrySet()) {
      assertEquals(
          "Unexpected ticker for " + entry.getKey(),
          entry.getValue(),
          settings.tickerFor(entry.getKey()));
    }
    assertNull(settings.tickerFor("BEDROCK"));
    assertEquals(1000, settings.quoteTicks());
  }

  @Test
  public void duplicateItemMappingsAreRejected() throws Exception {
    // WHEAT already belongs to AGRI; assigning it to FISH must fail.
    String yaml = replaceRequired(defaults(), "    COD,\n", "    WHEAT,\n");
    Exception error = assertThrows(Exception.class, () -> parse(yaml));
    assertNotNull(error.getMessage());
    assertFalse(error.getMessage().isBlank());
  }

  @Test
  public void invalidKeysMappingsAndValuesHaveActionableErrors() throws Exception {
    // The packaged defaults must load; otherwise all negative tests could pass spuriously.
    parse(defaults());
    String original = defaults();
    String[] invalidConfigurations = {
      replaceRequired(original, "refresh-seconds:", "refresh-second:"),
      replaceRequired(original, "    COD,\n", "    WHEAT,\n"),
      replaceRequired(original, "icon: COD", "icon: NOT_AN_ITEM"),
      replaceRequired(original, "refresh-seconds: 2", "refresh-seconds: 0"),
      replaceRequired(original, "market: OVERWORLD", "market: MISSING"),
      replaceRequired(original, "tradable: true", "tradable: maybe")
    };

    for (int i = 0; i < invalidConfigurations.length; i++) {
      String invalid = invalidConfigurations[i];
      Exception error =
          assertThrows(
              "Invalid configuration #" + (i + 1) + " was accepted",
              Exception.class,
              () -> parse(invalid));
      assertNotNull(error.getMessage());
      assertFalse(error.getMessage().isBlank());
    }
  }

  @Test
  public void savedIdentitiesCannotMoveButCanBePaused() throws Exception {
    Path root = Files.createTempDirectory("identities-");
    String original = defaults();

    MarketSettings initial = parse(original);
    initial.validateSavedIdentities(root, true);

    String paused = replaceRequired(original, "tradable: true", "tradable: false");
    parse(paused).validateSavedIdentities(root, false);

    String withSecondMarket =
        replaceRequired(
            original, "industries:\n", "  SECOND:\n    name: Second Exchange\nindustries:\n");
    String moved =
        replaceRequired(
            withSecondMarket, "  MINE:\n    market: OVERWORLD", "  MINE:\n    market: SECOND");

    MarketSettings modified = parse(moved);
    assertThrows(IOException.class, () -> modified.validateSavedIdentities(root, false));
  }

  private String customMarkets() throws Exception {
    return defaults()
            .substring(0, defaults().indexOf("markets:"))
            .replace("default-ticker: AGRI", "default-ticker: CRYSTAL")
        + """
        markets:
          SKY:
            name: Sky Exchange
        industries:
          ORE:
            market: SKY
            name: Cloud Mining
            icon: RAW_IRON
            items: [RAW_IRON]
            reference-price: 175
            volatility: 1.1
            events-enabled: true
            tradable: true
            daily-payout-limit: inherit
          CRYSTAL:
            market: SKY
            name: Sky Crystals
            icon: AMETHYST_SHARD
            items: [AMETHYST_SHARD]
            reference-price: 5000
            volatility: 2.5
            events-enabled: true
            tradable: true
            daily-payout-limit: inherit
        """;
  }

  @Test
  public void customConfigurationSelectsExplicitOrFirstTickerWithoutPackagedIdentities()
      throws Exception {
    var explicit = parse(customMarkets());
    assertEquals("CRYSTAL", explicit.defaultTicker());
    assertEquals(
        "ORE", parse(customMarkets().replace("default-ticker: CRYSTAL\n", "")).defaultTicker());
    // Paper treats YAML null and empty values as absent settings.
    for (String absent : new String[] {"null", ""}) {
      assertEquals(
          "ORE",
          parse(customMarkets().replace("default-ticker: CRYSTAL", "default-ticker: " + absent))
              .defaultTicker());
    }
    assertEquals("CRYSTAL", explicit.tickerFor("AMETHYST_SHARD"));
    assertNull(explicit.tickerFor("WHEAT"));
    assertFalse(explicit.industries().containsKey("AGRI"));
    assertFalse(explicit.markets().containsKey("OVERWORLD"));
    for (String invalid : new String[] {"MISSING", "''", "123"}) {
      var error =
          assertThrows(
              Exception.class,
              () ->
                  parse(
                      customMarkets()
                          .replace("default-ticker: CRYSTAL", "default-ticker: " + invalid)));
      assertTrue(error.getMessage(), error.getMessage().contains("default-ticker"));
    }
  }

  @Test
  public void entirelyCustomMarketsTradeAndRestoreInBothSettlementModes() throws Exception {
    var settings = parse(customMarkets());
    for (var mode : com.thaalur.quadstock.trading.TradePreview.SettlementMode.values()) {
      Path root = Files.createTempDirectory("custom-exchange-");
      var player = java.util.UUID.randomUUID();
      var gateway =
          new com.thaalur.quadstock.trading.TradingDesk.Gateway() {
            public String identity() {
              return "Test";
            }

            public int digits() {
              return 2;
            }

            public java.math.BigDecimal balance(java.util.UUID id) {
              return new java.math.BigDecimal("1000000");
            }

            public boolean pay(
                java.util.UUID id,
                com.thaalur.quadstock.trading.HoldingsLedger.Side side,
                java.math.BigDecimal amount) {
              return true;
            }
          };
      var policy =
          new com.thaalur.quadstock.trading.TradingPolicy(
              mode, "Test", 2, new java.math.BigDecimal("100000"));
      settings.validateSavedIdentities(root, true);
      try (var ledger = com.thaalur.quadstock.trading.HoldingsLedger.open(root)) {
        for (var industry : settings.industries().values()) {
          var market = com.thaalur.quadstock.data.LivingMarket.load(root, 0, industry, 1);
          market.advanceTo(24000);
          market.save();
          var desk =
              new com.thaalur.quadstock.trading.TradingDesk(
                  ledger, gateway, policy, industry.ticker(), industry.market());
          desk.configure(
              industry.ticker(), industry.market(), industry.dailyLimit(), 1000, 10000, true);
          var buy =
              desk.quote(
                  player,
                  com.thaalur.quadstock.trading.HoldingsLedger.Side.BUY,
                  10,
                  market.currentPrice(),
                  24000);
          desk.confirm(player, buy.id(), 24000);
          var sell =
              desk.quote(
                  player,
                  com.thaalur.quadstock.trading.HoldingsLedger.Side.SELL,
                  4,
                  market.currentPrice(),
                  24000);
          desk.confirm(player, sell.id(), 24000);
          assertEquals(6, desk.position(player).shares());
          assertEquals(
              market.observations(),
              com.thaalur.quadstock.data.LivingMarket.load(root, 24000, industry, 1)
                  .observations());
        }
      }
      try (var restored = com.thaalur.quadstock.trading.HoldingsLedger.open(root)) {
        assertEquals(6, restored.position(player, "ORE").shares());
        assertEquals(6, restored.position(player, "CRYSTAL").shares());
        assertEquals(0, restored.position(player, "AGRI").shares());
        assertTrue(restored.entries().stream().allMatch(e -> e.intent().market().equals("SKY")));
      }
      settings.validateSavedIdentities(root, false);
      // Default selection is navigation only; it does not change saved ticker identities.
      parse(customMarkets().replace("default-ticker: CRYSTAL", "default-ticker: ORE"))
          .validateSavedIdentities(root, false);
    }
  }

  @Test
  public void commerceSettingsValidateAndRetainDefaultsWhenOmitted() throws Exception {
    var defaults = parse(defaults());
    assertEquals(2, defaults.maxOutlookPercent(), 0);
    assertEquals(1, defaults.commerceInfluenceScale(), 0);
    assertEquals(
        com.thaalur.quadstock.commerce.CommerceSignal.Requirements.DEFAULT,
        defaults.commerceRequirements());
    String omitted =
        defaults()
            .replaceAll(
                "(?m)^ "
                    + " (max-outlook-percent|minimum-buyers|minimum-sellers|minimum-participants|minimum-pairs):.*\n",
                "");
    assertEquals(defaults.commerceRequirements(), parse(omitted).commerceRequirements());
    assertEquals(2, parse(omitted).maxOutlookPercent(), 0);
    String tuned =
        defaults()
            .replace("max-outlook-percent: 2.0", "max-outlook-percent: 5.0")
            .replace("minimum-buyers: 3", "minimum-buyers: 1")
            .replace("minimum-sellers: 2", "minimum-sellers: 1")
            .replace("minimum-participants: 4", "minimum-participants: 2")
            .replace("minimum-pairs: 3", "minimum-pairs: 1");
    assertEquals(2.5, parse(tuned).commerceInfluenceScale(), 0);
    assertEquals(
        new com.thaalur.quadstock.commerce.CommerceSignal.Requirements(1, 1, 2, 1),
        parse(tuned).commerceRequirements());
    for (String bad : new String[] {"-1", "101", ".NaN", "wrong"})
      assertThrows(
          Exception.class,
          () ->
              parse(defaults().replace("max-outlook-percent: 2.0", "max-outlook-percent: " + bad)));
    for (String key :
        new String[] {
          "minimum-buyers: 3", "minimum-sellers: 2", "minimum-participants: 4", "minimum-pairs: 3"
        })
      for (String bad : new String[] {"0", "1.5", "8193", "wrong"})
        assertThrows(
            Exception.class, () -> parse(defaults().replace(key, key.split(":")[0] + ": " + bad)));
    assertEquals(
        0,
        parse(tuned.replace("influence-scale: 1.0", "influence-scale: 0")).commerceInfluenceScale(),
        0);
    assertEquals(
        0,
        parse(tuned.replace("commerce:\n  enabled: true", "commerce:\n  enabled: false"))
            .commerceInfluenceScale(),
        0);
  }

  @Test
  public void industryEventOverridesInheritAndStoryReferencesAreValidated() throws Exception {
    String original = defaults();
    String overridden =
        original.replace(
            "story-collection: farming",
            "story-collection: farming\n    events:\n      special:\n        chance-percent: 25");
    var parsed = parse(overridden);
    assertEquals(
        25, parsed.industries().get("AGRI").eventSettings().value("special.chance-percent"), 0);
    assertEquals(
        1, parsed.industries().get("MINE").eventSettings().value("special.chance-percent"), 0);
    assertEquals(
        2,
        parsed
            .industries()
            .get("AGRI")
            .eventSettings()
            .value("special.max-move-percent-per-market-hour"),
        0);
    assertThrows(
        Exception.class,
        () -> parse(original.replace("story-collection: farming", "story-collection: missing")));
    assertThrows(
        Exception.class,
        () ->
            parse(
                original.replace(
                    "story-collection: farming",
                    "story-collection: farming\n"
                        + "    events:\n"
                        + "      special:\n"
                        + "        chance-percent: 101")));
    assertEquals(
        "generic",
        parse(original.replace("    story-collection: farming\n", ""))
            .industries()
            .get("AGRI")
            .storyCollection());
    assertThrows(
        Exception.class,
        () -> parse(original.replace("news-headline-limit: 4", "news-headline-limit: 0")));
  }

  @Test
  public void historyRetentionDefaultsToTwelveAndAcceptsBoundedWholeMonths() throws Exception {
    String yaml = defaults();
    assertEquals(12, parse(yaml).historyRetentionMonths());
    assertEquals(
        12,
        parse(yaml.replace("history:\n  retention-market-months: 12\n", ""))
            .historyRetentionMonths());
    assertEquals(
        3,
        parse(yaml.replace("retention-market-months: 12", "retention-market-months: 3"))
            .historyRetentionMonths());
    for (String bad : java.util.List.of("0", "13", "2.5", "'12'"))
      assertThrows(
          IOException.class,
          () ->
              parse(
                  yaml.replace("retention-market-months: 12", "retention-market-months: " + bad)));
    assertThrows(
        IOException.class,
        () -> parse(yaml.replace("retention-market-months: 12", "retention-months: 12")));
  }
}
