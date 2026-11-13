package com.thaalur.quadstock.events;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable, resolved global settings plus optional per-industry overrides. Percent values use 5
 * for 5%.
 */
public final class EventSettings {
  private static final Map<String, Double> DEFAULTS =
      Map.ofEntries(
          Map.entry("max-move-percent-per-market-hour", .5),
          Map.entry("response-half-life-market-hours", Math.log(.5) / Math.log(.965)),
          Map.entry("positive-weight", 4.0),
          Map.entry("negative-weight", 4.0),
          Map.entry("neutral-weight", 2.0),
          Map.entry("positive-impact-min-percent", 8.0),
          Map.entry("positive-impact-max-percent", 12.0),
          Map.entry("negative-impact-min-percent", 8.0),
          Map.entry("negative-impact-max-percent", 12.0),
          Map.entry("duration-min-market-hours", 18.0),
          Map.entry("duration-max-market-hours", 36.0),
          Map.entry("gap-min-market-hours", 12.0),
          Map.entry("gap-max-market-hours", 36.0),
          Map.entry("special.chance-percent", 1.0),
          Map.entry("special.positive-weight", 1.0),
          Map.entry("special.negative-weight", 1.0),
          Map.entry("special.positive-impact-min-percent", 20.0),
          Map.entry("special.positive-impact-max-percent", 40.0),
          Map.entry("special.negative-impact-min-percent", 20.0),
          Map.entry("special.negative-impact-max-percent", 40.0),
          Map.entry("special.duration-min-market-hours", 12.0),
          Map.entry("special.duration-max-market-hours", 36.0),
          Map.entry("special.cooldown-market-days", 14.0),
          Map.entry("special.response-half-life-market-hours", 3.0),
          Map.entry("special.max-move-percent-per-market-hour", 2.0));
  private final Map<String, Double> values;
  private final boolean specialEnabled;

  private EventSettings(Map<String, Double> values, boolean enabled) {
    this.values = Map.copyOf(values);
    specialEnabled = enabled;
  }

  public static EventSettings defaults() {
    return new EventSettings(DEFAULTS, true);
  }

  public double value(String key) {
    return values.get(key);
  }

  public boolean specialEnabled() {
    return specialEnabled;
  }

  /**
   * Missing keys inherit; unknown, nonfinite and inconsistent values fail with a configuration
   * path.
   */
  public static EventSettings read(ConfigurationSection section, EventSettings inherited)
      throws IOException {
    if (section == null) return inherited;
    var values = new LinkedHashMap<>(inherited.values);
    boolean enabled = inherited.specialEnabled;
    for (String key : section.getKeys(true)) {
      Object raw = section.get(key);
      if (key.equals("special") && raw instanceof ConfigurationSection) continue;
      if (key.equals("special.enabled")) {
        if (!(raw instanceof Boolean))
          throw new IOException(section.getCurrentPath() + "." + key + " must be boolean");
        enabled = (Boolean) raw;
      } else {
        if (!DEFAULTS.containsKey(key))
          throw new IOException("Unknown event setting " + section.getCurrentPath() + "." + key);
        if (!(raw instanceof Number n) || !Double.isFinite(n.doubleValue()))
          throw new IOException(section.getCurrentPath() + "." + key + " must be a finite number");
        double v = ((Number) raw).doubleValue();
        double min =
            key.contains("weight")
                    || key.contains("chance")
                    || key.contains("impact")
                    || key.contains("cooldown")
                ? 0
                : .01;
        double max =
            key.contains("impact")
                ? 90
                : key.contains("percent") ? 100 : key.contains("weight") ? 1000 : 8760;
        if (v < min || v > max)
          throw new IOException(
              section.getCurrentPath() + "." + key + " must be " + min + "–" + max);
        values.put(key, v);
      }
    }
    for (String prefix : new String[] {"", "special."}) {
      for (String range : new String[] {"positive-impact", "negative-impact", "duration"}) {
        String suffix = range.equals("duration") ? "-market-hours" : "-percent";
        if (values.get(prefix + range + "-min" + suffix)
            > values.get(prefix + range + "-max" + suffix))
          throw new IOException(
              section.getCurrentPath() + ": " + prefix + range + " minimum exceeds maximum");
      }
      if ((prefix.isEmpty() || enabled)
          && values.get(prefix + "positive-weight")
                  + values.get(prefix + "negative-weight")
                  + (prefix.isEmpty() ? values.get("neutral-weight") : 0)
              <= 0)
        throw new IOException(
            section.getCurrentPath() + ": " + prefix + "weights must have a positive total");
    }
    if (values.get("gap-min-market-hours") > values.get("gap-max-market-hours"))
      throw new IOException(section.getCurrentPath() + ": gap minimum exceeds maximum");
    if (enabled
        && (values.get("special.response-half-life-market-hours")
                > values.get("response-half-life-market-hours")
            || values.get("special.max-move-percent-per-market-hour")
                < values.get("max-move-percent-per-market-hour")))
      throw new IOException(
          section.getCurrentPath()
              + ": special events must respond at least as fast as ordinary events");
    return new EventSettings(values, enabled);
  }

  public boolean categoryEnabled(String category) {
    if (category.startsWith("special-"))
      return specialEnabled
          && value("special.chance-percent") > 0
          && value("special." + category.substring(8) + "-weight") > 0;
    return value(category + "-weight") > 0;
  }
}
