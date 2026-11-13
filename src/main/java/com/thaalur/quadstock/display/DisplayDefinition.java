package com.thaalur.quadstock.display;

import com.thaalur.quadstock.data.*;
import java.util.*;

/** Immutable desired state. One location owns one display, including mirrored sign sides. */
public record DisplayDefinition(
    UUID id,
    Anchor anchor,
    long revision,
    Map<String, String> options,
    Map<String, String> originals,
    boolean removed) {
  public enum Type {
    SUMMARY,
    PRICE,
    CHART,
    NEWS,
    ACTIVITY
  }

  public enum Surface {
    SIGN,
    FLOATING
  }

  public enum Mode {
    HOUR,
    DAY,
    WEEK,
    MONTH,
    LIVE;

    public ChartWindow window(long tick) {
      return this == LIVE
          ? ChartWindow.live(tick, TimeRange.HOUR.ticks(), 9)
          : ChartWindow.history(tick, TimeRange.valueOf(name()).ticks(), 9, null);
    }

    public TimeRange range() {
      return this == LIVE ? TimeRange.HOUR : TimeRange.valueOf(name());
    }
  }

  public record Anchor(UUID world, int x, int y, int z, String material, String blockData) {
    public Anchor {
      Objects.requireNonNull(world);
      Objects.requireNonNull(material);
      Objects.requireNonNull(blockData);
      if (Math.abs((long) x) > 30_000_000
          || Math.abs((long) z) > 30_000_000
          || y < -2048
          || y > 2047
          || !material.matches("[A-Z0-9_]+")
          || blockData.isBlank()
          || blockData.length() > 4096)
        throw new IllegalArgumentException("Invalid display anchor");
    }

    public String locationKey() {
      return world + ":" + x + ":" + y + ":" + z;
    }
  }

  public static final Map<String, String> DEFAULTS =
      Map.ofEntries(
          Map.entry("type", "SUMMARY"),
          Map.entry("surface", "FLOATING"),
          Map.entry("period", "DAY"),
          Map.entry("sides", "FRONT"),
          Map.entry("yaw", "0"),
          Map.entry("height", "1.5"),
          Map.entry("x", "0"),
          Map.entry("z", "0"),
          Map.entry("size", "1"),
          Map.entry("accent", "#55FFFF"),
          Map.entry("scroll-ms", "200"),
          Map.entry("pause-ms", "2000"));

  public DisplayDefinition {
    Objects.requireNonNull(id);
    Objects.requireNonNull(anchor);
    options = Map.copyOf(options);
    originals = Map.copyOf(originals);
    if (revision < 0 || !options.keySet().equals(keys()))
      throw new IllegalArgumentException("Invalid display options/revision");
    if (!value(options, "ticker").matches("[A-Z][A-Z0-9_]{0,15}"))
      throw new IllegalArgumentException("Invalid ticker");
    Type.valueOf(value(options, "type"));
    Surface.valueOf(value(options, "surface"));
    Mode.valueOf(value(options, "period"));
    if (!Set.of("FRONT", "BACK", "BOTH").contains(value(options, "sides")))
      throw new IllegalArgumentException("sides: FRONT, BACK or BOTH");
    if (!value(options, "accent").matches("#[0-9a-fA-F]{6}"))
      throw new IllegalArgumentException("accent: #RRGGBB");
    bounded(options, "yaw", -180, 180);
    var aligned = new HashMap<>(options);
    aligned.put("yaw", Float.toString(DisplayMount.snap(Double.parseDouble(options.get("yaw")))));
    options = Map.copyOf(aligned);
    bounded(options, "height", 0, 5);
    bounded(options, "x", -3, 3);
    bounded(options, "z", -3, 3);
    bounded(options, "size", 0.25, 3);
    bounded(options, "scroll-ms", 100, 2000);
    bounded(options, "pause-ms", 500, 10000);
    if (originals.size() > 8) throw new IllegalArgumentException("Invalid sign backup");
    String supports = originals.getOrDefault("supports", "");
    if (!supports.isEmpty()) {
      String[] locations = supports.split("\n", -1);
      if (locations.length > 128) throw new IllegalArgumentException("Too many sign supports");
      for (String location : locations) {
        String[] parts = location.split(":", -1);
        if (parts.length != 4 || !parts[0].equals(anchor.world().toString()))
          throw new IllegalArgumentException("Invalid support location");
        new Anchor(
            anchor.world(),
            Integer.parseInt(parts[1]),
            Integer.parseInt(parts[2]),
            Integer.parseInt(parts[3]),
            "STONE",
            "minecraft:stone");
      }
    }
  }

  private static Set<String> keys() {
    var k = new HashSet<>(DEFAULTS.keySet());
    k.add("ticker");
    return k;
  }

  private static String value(Map<String, String> m, String k) {
    return Objects.requireNonNull(m.get(k));
  }

  private static void bounded(Map<String, String> m, String k, double min, double max) {
    double v = Double.parseDouble(value(m, k));
    if (!Double.isFinite(v) || v < min || v > max)
      throw new IllegalArgumentException(k + ": " + min + ".." + max);
  }

  public static DisplayDefinition create(Anchor anchor, String ticker, Surface surface, float yaw) {
    var opts = new HashMap<>(DEFAULTS);
    opts.put("ticker", ticker);
    opts.put("surface", surface.name());
    opts.put("yaw", String.valueOf(yaw));
    return new DisplayDefinition(UUID.randomUUID(), anchor, 0, opts, Map.of(), false);
  }

  public DisplayDefinition option(String key, String value) {
    var opts = new HashMap<>(options);
    if (!opts.containsKey(key))
      throw new IllegalArgumentException("Unknown display property: " + key);
    opts.put(
        key,
        Set.of("ticker", "type", "surface", "period", "sides").contains(key)
            ? value.toUpperCase(Locale.ROOT)
            : value);
    return new DisplayDefinition(id, anchor, revision, opts, originals, removed);
  }

  public DisplayDefinition saved(Map<String, String> backup, boolean removing) {
    return new DisplayDefinition(id, anchor, revision + 1, options, backup, removing);
  }

  /** Includes persisted supports even when their sign's world/chunk is unloaded. */
  public Set<String> protectedLocations() {
    var result = new HashSet<String>();
    result.add(anchor.locationKey());
    String supports = originals.getOrDefault("supports", "");
    if (!supports.isEmpty()) result.addAll(Arrays.asList(supports.split("\n")));
    return Set.copyOf(result);
  }

  public Type type() {
    return Type.valueOf(options.get("type"));
  }

  public Surface surface() {
    return Surface.valueOf(options.get("surface"));
  }

  public Mode mode() {
    return Mode.valueOf(options.get("period"));
  }

  public String ticker() {
    return options.get("ticker");
  }

  public double number(String key) {
    return Double.parseDouble(options.get(key));
  }

  public boolean side(String side) {
    return options.get("sides").equals("BOTH") || options.get("sides").equals(side);
  }
}
