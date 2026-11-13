package com.thaalur.quadstock.display;

import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** Optional display policy; absent sections use packaged defaults without rewriting admin files. */
public record DisplaySettings(Set<String> allowed, int maximum, int viewDistance) {
  public static final List<String> DEFAULTS =
      List.of(
          "STONE",
          "STONE_BRICKS",
          "POLISHED_ANDESITE",
          "DEEPSLATE_BRICKS",
          "QUARTZ_BLOCK",
          "SMOOTH_QUARTZ",
          "BRICKS",
          "OAK_PLANKS",
          "SPRUCE_PLANKS",
          "DARK_OAK_PLANKS",
          "IRON_BLOCK",
          "GOLD_BLOCK",
          "COPPER_BLOCK",
          "OAK_SIGN",
          "OAK_WALL_SIGN",
          "OAK_HANGING_SIGN",
          "OAK_WALL_HANGING_SIGN",
          "SPRUCE_SIGN",
          "SPRUCE_WALL_SIGN",
          "SPRUCE_HANGING_SIGN",
          "SPRUCE_WALL_HANGING_SIGN");

  public DisplaySettings {
    allowed = Collections.unmodifiableSet(new LinkedHashSet<>(allowed));
  }

  public static DisplaySettings read(ConfigurationSection root, Predicate<String> isBlock)
      throws IOException {
    if (!root.contains("displays"))
      return new DisplaySettings(new LinkedHashSet<>(DEFAULTS), 256, 32);
    var s = root.getConfigurationSection("displays");
    if (s == null) throw new IOException("displays must be a section");
    for (String key : s.getKeys(false))
      if (!Set.of("allowed-anchor-materials", "max-displays", "view-distance").contains(key))
        throw new IOException("Unknown setting displays." + key);
    Object raw = s.get("allowed-anchor-materials");
    if (!(raw instanceof List<?> list))
      throw new IOException("displays.allowed-anchor-materials must be a list");
    var allowed = new LinkedHashSet<String>();
    for (Object entry : list) {
      if (!(entry instanceof String name) || !name.matches("[A-Z0-9_]+") || !isBlock.test(name))
        throw new IOException("Unknown/non-block display anchor: " + entry);
      if (!allowed.add(name)) throw new IOException("Duplicate display anchor: " + name);
    }
    return new DisplaySettings(
        allowed, integer(s, "max-displays", 256, 1, 2048), integer(s, "view-distance", 32, 8, 64));
  }

  private static int integer(ConfigurationSection s, String key, int fallback, int min, int max)
      throws IOException {
    Object v = s.get(key, fallback);
    if (!(v instanceof Number n)
        || n.doubleValue() != n.intValue()
        || n.intValue() < min
        || n.intValue() > max)
      throw new IOException("displays." + key + " must be an integer in " + min + ".." + max);
    return ((Number) v).intValue();
  }

  public static boolean blockMaterial(String name) {
    try {
      return Material.valueOf(name).isBlock() && !Material.valueOf(name).isAir();
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
