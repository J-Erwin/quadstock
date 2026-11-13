package com.thaalur.quadstock.events;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Validated plain-text narrative library. Story count never controls economic probability. */
public final class StoryCatalog {
  public static final List<String> CATEGORIES =
      List.of("positive", "negative", "neutral", "special-positive", "special-negative");

  public record Story(String id, String headline, String description) {
    public String headline(String company, String ticker) {
      return resolve(headline, company, ticker);
    }

    public String description(String company, String ticker) {
      return resolve(description, company, ticker);
    }

    private static String resolve(String text, String company, String ticker) {
      // A single pass prevents placeholders embedded in an administrator's company name being
      // expanded again.
      var matcher = java.util.regex.Pattern.compile("\\{company}|\\{ticker}").matcher(text);
      return matcher.replaceAll(
          m ->
              java.util.regex.Matcher.quoteReplacement(
                  m.group().equals("{company}") ? company : ticker));
    }
  }

  private final Map<String, Map<String, List<Story>>> collections;

  private StoryCatalog(Map<String, Map<String, List<Story>>> collections) {
    this.collections = Map.copyOf(collections);
  }

  public static StoryCatalog load(Path file) throws Exception {
    var yaml = new YamlConfiguration();
    yaml.load(file.toFile());
    return parse(yaml);
  }

  /**
   * Bundled defaults for model-only callers; server startup always loads the administrator file.
   */
  public static StoryCatalog bundled() throws IOException {
    try (var input = StoryCatalog.class.getResourceAsStream("/stories.yml")) {
      if (input == null) throw new IOException("Missing bundled stories.yml");
      var yaml = new YamlConfiguration();
      yaml.load(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
      return parse(yaml);
    } catch (org.bukkit.configuration.InvalidConfigurationException e) {
      throw new IOException("Invalid bundled stories.yml", e);
    }
  }

  private static StoryCatalog parse(YamlConfiguration yaml) throws IOException {
    for (String key : yaml.getKeys(false))
      if (!Set.of("version", "collections").contains(key))
        throw new IOException("stories.yml: unknown " + key);
    if (!(yaml.get("version") instanceof Number n) || n.doubleValue() != 1)
      throw new IOException("stories.yml version must be 1");
    var section = yaml.getConfigurationSection("collections");
    if (section == null || section.getKeys(false).isEmpty() || section.getKeys(false).size() > 128)
      throw new IOException("stories.yml requires 1–128 collections");
    Map<String, Map<String, List<Story>>> collections = new LinkedHashMap<>();
    for (String name : section.getKeys(false)) {
      if (!validId(name)) throw new IOException("Invalid story collection ID: " + name);
      ConfigurationSection c = section.getConfigurationSection(name);
      if (c == null) throw new IOException("Story collection must be a section: " + name);
      Map<String, List<Story>> categories = new LinkedHashMap<>();
      Set<String> ids = new HashSet<>();
      for (String category : c.getKeys(false)) {
        if (!CATEGORIES.contains(category))
          throw new IOException("Unknown story category " + name + "." + category);
        Object raw = c.get(category);
        if (!(raw instanceof List<?> entries) || entries.size() > 100)
          throw new IOException(name + "." + category + " must be a list of up to 100 stories");
        List<Story> stories = new ArrayList<>();
        Set<String> texts = new HashSet<>();
        for (Object entry : entries) {
          if (!(entry instanceof Map<?, ?> fields)
              || !fields.keySet().equals(Set.of("id", "headline", "description")))
            throw new IOException(
                name + "." + category + ": each story requires only id, headline, description");
          String id = text(fields.get("id"), 64, name),
              headline = text(fields.get("headline"), 100, name),
              description = text(fields.get("description"), 400, name);
          if (!validId(id) || !ids.add(id))
            throw new IOException(name + ": invalid/duplicate story ID " + id);
          if (!texts.add(
              headline.toLowerCase(Locale.ROOT) + "\n" + description.toLowerCase(Locale.ROOT)))
            throw new IOException(name + ": duplicate story text");
          stories.add(new Story(id, headline, description));
        }
        categories.put(category, List.copyOf(stories));
      }
      collections.put(name, Map.copyOf(categories));
    }
    if (!collections.containsKey("generic"))
      throw new IOException("stories.yml requires a generic collection");
    return new StoryCatalog(collections);
  }

  private static boolean validId(String id) {
    return id.matches("[a-z][a-z0-9_-]{0,63}");
  }

  private static String text(Object value, int max, String path) throws IOException {
    if (!(value instanceof String s)
        || s.isBlank()
        || s.length() > max
        || s.chars().anyMatch(Character::isISOControl))
      throw new IOException(path + ": story text must contain 1–" + max + " printable characters");
    String remaining = s.replace("{company}", "").replace("{ticker}", "");
    if (remaining.contains("{") || remaining.contains("}"))
      throw new IOException(path + ": unknown story placeholder");
    return s;
  }

  public void validate(String collection, EventSettings settings) throws IOException {
    if (!collections.containsKey(collection))
      throw new IOException("Unknown story-collection: " + collection);
    for (String category : CATEGORIES)
      if (settings.categoryEnabled(category) && stories(collection, category).isEmpty())
        throw new IOException(collection + ": enabled category has no stories: " + category);
  }

  public List<Story> stories(String collection, String category) {
    return collections.getOrDefault(collection, Map.of()).getOrDefault(category, List.of());
  }

  public int size() {
    return collections.values().stream()
        .flatMap(m -> m.values().stream())
        .mapToInt(List::size)
        .sum();
  }
}
