package com.thaalur.quadstock.config;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Strict startup configuration: unknown keys and unsafe identity changes fail before activation.
 */
public record MarketSettings(
    String defaultTicker,
    double refreshSeconds,
    int historyRetentionMonths,
    boolean tradingEnabled,
    long quoteTicks,
    int maxShares,
    boolean reportsEnabled,
    long reportTicks,
    long alertCooldownTicks,
    boolean commerceEnabled,
    double influenceScale,
    double maxOutlookPercent,
    com.thaalur.quadstock.commerce.CommerceSignal.Requirements commerceRequirements,
    com.thaalur.quadstock.events.StoryCatalog stories,
    int newsHeadlineLimit,
    Map<String, String> markets,
    Map<String, Industry> industries) {
  /**
   * One configured ticker. The internal referencePrice field is the YAML reference-price: it also
   * governs future mean reversion. A null dailyLimit inherits the settlement policy. Item names are
   * exact Paper material names; ticker and market IDs become persistent identities.
   */
  public record Industry(
      String ticker,
      String market,
      String name,
      String icon,
      Set<String> items,
      double referencePrice,
      double volatility,
      boolean eventsEnabled,
      boolean tradable,
      BigDecimal dailyLimit,
      String storyCollection,
      com.thaalur.quadstock.events.EventSettings eventSettings,
      long shareSupply,
      long maxSharesPerPlayer) {
    /** Compatibility constructor with unlimited supply and player holdings. */
    public Industry(
        String ticker,
        String market,
        String name,
        String icon,
        Set<String> items,
        double referencePrice,
        double volatility,
        boolean eventsEnabled,
        boolean tradable,
        BigDecimal dailyLimit,
        String storyCollection,
        com.thaalur.quadstock.events.EventSettings eventSettings) {
      this(
          ticker,
          market,
          name,
          icon,
          items,
          referencePrice,
          volatility,
          eventsEnabled,
          tradable,
          dailyLimit,
          storyCollection,
          eventSettings,
          Long.MAX_VALUE,
          Long.MAX_VALUE);
    }

    /** Explicit model settings with generic narratives and the standard event policy. */
    public Industry(
        String ticker,
        String market,
        String name,
        String icon,
        Set<String> items,
        double referencePrice,
        double volatility,
        boolean eventsEnabled,
        boolean tradable,
        BigDecimal dailyLimit) {
      this(
          ticker,
          market,
          name,
          icon,
          items,
          referencePrice,
          volatility,
          eventsEnabled,
          tradable,
          dailyLimit,
          "generic",
          com.thaalur.quadstock.events.EventSettings.defaults());
    }
  }

  /**
   * Defensively copies ordered market and industry maps. Direct construction does not run YAML
   * validation.
   */
  public MarketSettings {
    markets = Collections.unmodifiableMap(new LinkedHashMap<>(markets));
    industries = Collections.unmodifiableMap(new LinkedHashMap<>(industries));
  }

  /**
   * Loads strict YAML using Paper’s material registry; call with Paper initialized. Rejects unknown
   * keys, invalid ranges and overlapping item assignments. Does not apply or write settings.
   */
  public static MarketSettings load(Path file) throws Exception {
    var result = load(file, s -> Material.valueOf(s).isItem() && !s.equals("AIR"));
    var yaml = new YamlConfiguration();
    yaml.load(file.toFile());
    com.thaalur.quadstock.display.DisplaySettings.read(
        yaml, com.thaalur.quadstock.display.DisplaySettings::blockMaterial);
    return result;
  }

  /**
   * Loads the same schema with an injected item-name validator, allowing offline validation tests.
   * The predicate must reject non-item materials; production uses the Paper registry.
   */
  public static MarketSettings load(Path file, java.util.function.Predicate<String> isItem)
      throws Exception {
    var y = new YamlConfiguration();
    y.load(file.toFile());
    keys(
        y,
        "",
        Set.of(
            "history",
            "displays",
            "events",
            "version",
            "default-ticker",
            "ui",
            "trading",
            "reports",
            "commerce",
            "markets",
            "industries"));
    com.thaalur.quadstock.display.DisplaySettings.read(
        y,
        name -> {
          try {
            return Material.valueOf(name) != Material.AIR;
          } catch (IllegalArgumentException e) {
            return false;
          }
        });
    if (number(y, "version", 1, 1) != 1) throw new IOException("version must be 1");
    var history = optionalSection(y, "history");
    if (history != null) keys(history, "history", Set.of("retention-market-months"));
    int retentionMonths =
        history == null
            ? com.thaalur.quadstock.data.LivingMarket.DEFAULT_RETENTION_MONTHS
            : optionalInteger(
                history,
                "retention-market-months",
                com.thaalur.quadstock.data.LivingMarket.DEFAULT_RETENTION_MONTHS,
                1,
                com.thaalur.quadstock.data.LivingMarket.MAX_RETENTION_MONTHS);
    var ui = section(y, "ui");
    keys(ui, "ui", Set.of("refresh-seconds"));
    var tr = section(y, "trading");
    keys(tr, "trading", Set.of("enabled", "quote-market-minutes", "max-shares-per-order"));
    var rp = section(y, "reports");
    keys(
        rp,
        "reports",
        Set.of(
            "enabled",
            "interval-market-hours",
            "alert-cooldown-market-hours",
            "news-headline-limit"));
    var co = section(y, "commerce");
    keys(
        co,
        "commerce",
        Set.of(
            "enabled",
            "influence-scale",
            "max-outlook-percent",
            "minimum-buyers",
            "minimum-sellers",
            "minimum-participants",
            "minimum-pairs"));
    var stories =
        com.thaalur.quadstock.events.StoryCatalog.load(
            file.toAbsolutePath().getParent().resolve("stories.yml"));
    var eventDefaults =
        com.thaalur.quadstock.events.EventSettings.read(
            optionalSection(y, "events"), com.thaalur.quadstock.events.EventSettings.defaults());
    var ms = section(y, "markets");
    var ts = section(y, "industries");
    if (ms.getKeys(false).isEmpty()
        || ms.getKeys(false).size() > 18
        || ts.getKeys(false).isEmpty()
        || ts.getKeys(false).size() > 18)
      throw new IOException("Configure 1–18 markets and 1–18 industries");
    Map<String, String> markets = new LinkedHashMap<>();
    Map<String, Industry> industries = new LinkedHashMap<>();
    Set<String> assigned = new HashSet<>();
    for (String id : ms.getKeys(false)) {
      id(id);
      var m = section(ms, id);
      keys(m, "markets." + id, Set.of("name"));
      markets.put(id, string(m, "name"));
    }
    for (String ticker : ts.getKeys(false)) {
      id(ticker);
      var t = section(ts, ticker);
      keys(
          t,
          "industries." + ticker,
          Set.of(
              "events",
              "story-collection",
              "market",
              "name",
              "icon",
              "items",
              "reference-price",
              "volatility",
              "events-enabled",
              "tradable",
              "daily-payout-limit",
              "share-supply",
              "max-shares-per-player"));
      String market = string(t, "market"), name = string(t, "name"), icon = string(t, "icon");
      if (!markets.containsKey(market))
        throw new IOException(ticker + ".market refers to unknown market " + market);
      material(icon, ticker + ".icon", isItem);
      Set<String> items = new LinkedHashSet<>();
      if (!t.isList("items") || t.getList("items").isEmpty())
        throw new IOException(ticker + ".items must be a nonempty list");
      for (Object value : t.getList("items")) {
        if (!(value instanceof String item))
          throw new IOException(ticker + ".items must contain material names");
        material(item, ticker + ".items", isItem);
        if (!items.add(item) || !assigned.add(item))
          throw new IOException(
              "Item " + item + " is assigned more than once; one item must route to one ticker");
      }
      Object limit = t.get("daily-payout-limit");
      BigDecimal amount = null;
      if (!"inherit".equals(limit))
        try {
          amount = new BigDecimal(String.valueOf(limit));
          if (amount.signum() <= 0
              || amount.scale() > 4
              || amount.compareTo(new BigDecimal("1000000000")) > 0)
            throw new NumberFormatException();
        } catch (NumberFormatException e) {
          throw new IOException(
              ticker
                  + ".daily-payout-limit must be inherit or positive money up to 1,000,000,000 (max"
                  + " 4 decimals)");
        }
      String collection =
          t.contains("story-collection") ? string(t, "story-collection") : "generic";
      var eventSettings =
          com.thaalur.quadstock.events.EventSettings.read(
              optionalSection(t, "events"), eventDefaults);
      stories.validate(collection, eventSettings);
      industries.put(
          ticker,
          new Industry(
              ticker,
              market,
              name,
              icon,
              Set.copyOf(items),
              number(t, "reference-price", 1, 5000),
              number(t, "volatility", 0, 5),
              bool(t, "events-enabled"),
              bool(t, "tradable"),
              amount,
              collection,
              eventSettings,
              shareLimit(t, "share-supply", ticker),
              shareLimit(t, "max-shares-per-player", ticker)));
    }
    String defaultTicker =
        y.getKeys(false).contains("default-ticker")
            ? string(y, "default-ticker")
            : industries.keySet().iterator().next();
    if (!industries.containsKey(defaultTicker))
      throw new IOException("default-ticker must name a configured industry: " + defaultTicker);
    double minutes = number(tr, "quote-market-minutes", 1, 1440);
    double shares = number(tr, "max-shares-per-order", 1, 10000);
    if (shares != (int) shares) throw new IOException("max-shares-per-order must be an integer");
    return new MarketSettings(
        defaultTicker,
        number(ui, "refresh-seconds", .5, 30),
        retentionMonths,
        bool(tr, "enabled"),
        Math.round(minutes * 1000 / 60),
        (int) shares,
        bool(rp, "enabled"),
        Math.round(number(rp, "interval-market-hours", 1, 168) * 1000),
        Math.round(number(rp, "alert-cooldown-market-hours", 1, 168) * 1000),
        bool(co, "enabled"),
        number(co, "influence-scale", 0, 1),
        optionalNumber(co, "max-outlook-percent", 2, 0, 100),
        new com.thaalur.quadstock.commerce.CommerceSignal.Requirements(
            optionalInteger(co, "minimum-buyers", 3, 1, 4096),
            optionalInteger(co, "minimum-sellers", 2, 1, 4096),
            optionalInteger(co, "minimum-participants", 4, 2, 8192),
            optionalInteger(co, "minimum-pairs", 3, 1, 4096)),
        stories,
        optionalInteger(rp, "news-headline-limit", 4, 1, 18),
        markets,
        industries);
  }

  /** Returns the unique configured ticker for an exact item material name, or null if unmapped. */
  public String tickerFor(String item) {
    return industries.values().stream()
        .filter(i -> i.items.contains(item))
        .map(Industry::ticker)
        .findFirst()
        .orElse(null);
  }

  /**
   * Rejects removal or reassignment of saved tickers. With write=false this is read-only; with
   * write=true it atomically replaces the registry, adding newly configured identities. The parent
   * directory must exist. This registry is not a holdings store.
   */
  public void validateSavedIdentities(Path root, boolean write) throws IOException {
    var saved = new Properties();
    var bytes = com.thaalur.quadstock.persistence.StateStore.read(root, "industry-identities");
    if (bytes.isPresent()) saved.load(new java.io.ByteArrayInputStream(bytes.get()));
    for (String ticker : saved.stringPropertyNames())
      if (!industries.containsKey(ticker)
          || !industries.get(ticker).market.equals(saved.getProperty(ticker)))
        throw new IOException(
            "Saved ticker "
                + ticker
                + " cannot be removed or moved to another market; pause it with tradable: false");
    if (write) {
      industries.values().forEach(i -> saved.setProperty(i.ticker, i.market));
      var out = new java.io.ByteArrayOutputStream();
      saved.store(out, "Permanent ticker to market assignments");
      com.thaalur.quadstock.persistence.StateStore.write(
          root, "industry-identities", out.toByteArray());
    }
  }

  private static void id(String s) throws IOException {
    if (!s.matches("[A-Z][A-Z0-9_]{0,15}"))
      throw new IOException(
          "ID " + s + " must be 1–16 uppercase letters/digits/underscores, starting with a letter");
  }

  private static void material(String s, String path, java.util.function.Predicate<String> isItem)
      throws IOException {
    try {
      if (!isItem.test(s)) throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw new IOException(path + ": unknown/non-item material " + s);
    }
  }

  private static ConfigurationSection optionalSection(ConfigurationSection section, String key)
      throws IOException {
    if (!section.contains(key)) return null;
    return section(section, key);
  }

  private static ConfigurationSection section(ConfigurationSection s, String key)
      throws IOException {
    var v = s.getConfigurationSection(key);
    if (v == null) throw new IOException(s.getCurrentPath() + "." + key + " must be a section");
    return v;
  }

  private static String string(ConfigurationSection s, String key) throws IOException {
    Object v = s.get(key);
    if (!(v instanceof String text) || text.isBlank() || text.length() > 64)
      throw new IOException(s.getCurrentPath() + "." + key + " must be text (1–64 characters)");
    return text;
  }

  private static boolean bool(ConfigurationSection s, String key) throws IOException {
    Object v = s.get(key);
    if (!(v instanceof Boolean b))
      throw new IOException(s.getCurrentPath() + "." + key + " must be true or false");
    return b;
  }

  /** Effective multiplier for the normalized 2% evidence signal; zero when disabled. */
  public double commerceInfluenceScale() {
    return commerceEnabled ? influenceScale * maxOutlookPercent / 2.0 : 0;
  }

  private static double optionalNumber(
      ConfigurationSection s, String key, double fallback, double min, double max)
      throws IOException {
    return s.contains(key) ? number(s, key, min, max) : fallback;
  }

  private static int optionalInteger(
      ConfigurationSection s, String key, int fallback, int min, int max) throws IOException {
    double value = optionalNumber(s, key, fallback, min, max);
    if (value != (int) value)
      throw new IOException(s.getCurrentPath() + "." + key + " must be a whole number");
    return (int) value;
  }

  private static double number(ConfigurationSection s, String key, double min, double max)
      throws IOException {
    Object v = s.get(key);
    if (!(v instanceof Number n)
        || !Double.isFinite(n.doubleValue())
        || n.doubleValue() < min
        || n.doubleValue() > max)
      throw new IOException(
          s.getCurrentPath() + "." + key + " must be between " + min + " and " + max);
    return n.doubleValue();
  }

  private static long shareLimit(ConfigurationSection section, String key, String ticker)
      throws IOException {
    Object value = section.get(key);
    if (value == null || "unlimited".equals(value)) return Long.MAX_VALUE;
    if ((value instanceof Integer || value instanceof Long) && ((Number) value).longValue() >= 0)
      return ((Number) value).longValue();
    throw new IOException(
        "industries."
            + ticker
            + "."
            + key
            + " must be unlimited or a whole number from 0 to 9223372036854775807");
  }

  private static void keys(ConfigurationSection s, String path, Set<String> allowed)
      throws IOException {
    for (String key : s.getKeys(false))
      if (!allowed.contains(key)) throw new IOException("Unknown setting " + path + "." + key);
  }
}
