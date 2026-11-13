package com.thaalur.quadstock.config;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Optional, independently configurable native Tab presentation and resource-pack delivery. */
public record TabSettings(
    boolean enabled,
    double refreshSeconds,
    int rowsPerPage,
    int pageSeconds,
    String packUrl,
    String packSha1,
    Set<String> iconMaterials) {
  public TabSettings {
    iconMaterials = Set.copyOf(iconMaterials);
  }

  /** Missing files use defaults; existing files are validated without modification. */
  public static TabSettings load(Path file) throws IOException {
    var y = new YamlConfiguration();
    if (Files.exists(file)) {
      try {
        y.load(file.toFile());
      } catch (Exception e) {
        throw new IOException("Cannot read tab.yml", e);
      }
    }
    keys(
        y,
        Set.of(
            "enabled",
            "refresh-seconds",
            "rows-per-page",
            "page-seconds",
            "resource-pack",
            "icon-materials"));
    Object enabled = y.get("enabled", true);
    if (!(enabled instanceof Boolean)) throw new IOException("tab.enabled must be true or false");
    double refresh = number(y, "refresh-seconds", 1, .25, 30);
    int rows = integer(y, "rows-per-page", 6, 1, 12);
    int pages = integer(y, "page-seconds", 8, 2, 60);
    String url = "", hash = "";
    if (y.contains("resource-pack")) {
      var pack = y.getConfigurationSection("resource-pack");
      if (pack == null) throw new IOException("tab.resource-pack must be a section");
      keys(pack, Set.of("url", "sha1"));
      url = text(pack, "url");
      hash = text(pack, "sha1").toLowerCase(java.util.Locale.ROOT);
    }
    if (!url.isEmpty()) {
      try {
        var uri = URI.create(url);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
            || uri.getHost() == null
            || uri.getUserInfo() != null
            || uri.getFragment() != null) throw new IllegalArgumentException();
      } catch (IllegalArgumentException e) {
        throw new IOException(
            "tab.resource-pack.url must be an absolute HTTP(S) URL without credentials or fragment",
            e);
      }
    }
    if (!hash.isEmpty() && !hash.matches("[0-9a-f]{40}"))
      throw new IOException("tab.resource-pack.sha1 must be blank or 40 hexadecimal characters");
    Set<String> icons = defaultIcons();
    if (y.contains("icon-materials")) {
      Object value = y.get("icon-materials");
      if (!(value instanceof java.util.List<?> list))
        throw new IOException("tab.icon-materials must be a list");
      icons = new LinkedHashSet<>();
      for (Object item : list) {
        if (!(item instanceof String material) || !material.matches("[A-Z][A-Z0-9_]{0,63}"))
          throw new IOException("tab.icon-materials must contain uppercase material names");
        icons.add(material);
      }
    }
    return new TabSettings((Boolean) enabled, refresh, rows, pages, url, hash, icons);
  }

  /** Legacy material names retained for existing administrator-supplied packs; no artwork. */
  public static Set<String> defaultIcons() throws IOException {
    try (var stream = TabSettings.class.getResourceAsStream("/tab-icons.txt")) {
      if (stream == null) throw new IOException("Missing default Tab icon manifest");
      return Set.copyOf(
          new String(stream.readAllBytes(), StandardCharsets.UTF_8)
              .lines()
              .filter(s -> !s.isBlank())
              .toList());
    }
  }

  private static void keys(ConfigurationSection section, Set<String> allowed) throws IOException {
    for (String key : section.getKeys(false))
      if (!allowed.contains(key)) throw new IOException("Unknown tab setting: " + key);
  }

  private static String text(ConfigurationSection section, String key) throws IOException {
    Object value = section.get(key, "");
    if (!(value instanceof String text) || text.length() > 2048)
      throw new IOException("tab." + key + " must be text");
    return text.trim();
  }

  private static double number(
      ConfigurationSection s, String key, double fallback, double min, double max)
      throws IOException {
    Object value = s.get(key, fallback);
    if (!(value instanceof Number n)
        || !Double.isFinite(n.doubleValue())
        || n.doubleValue() < min
        || n.doubleValue() > max)
      throw new IOException("tab." + key + " must be between " + min + " and " + max);
    return n.doubleValue();
  }

  private static int integer(ConfigurationSection s, String key, int fallback, int min, int max)
      throws IOException {
    double value = number(s, key, fallback, min, max);
    if (value != (int) value) throw new IOException("tab." + key + " must be an integer");
    return (int) value;
  }
}
