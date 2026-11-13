package com.thaalur.quadstock.events;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.Test;

public class StoryCatalogTest {
  private StoryCatalog parse(String text) throws Exception {
    Path file = Files.createTempFile("stories-", ".yml");
    try {
      Files.writeString(file, text);
      return StoryCatalog.load(file);
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private String sample() {
    return "version: 1\n"
        + "collections:\n"
        + "  generic:\n"
        + "    positive:\n"
        + "      - id: first\n"
        + "        headline: '{company} grows'\n"
        + "        description: '{ticker} outlook improves.'\n";
  }

  @Test
  public void bundledCollectionsHaveAllCategoriesAndAtLeast221Stories() throws Exception {
    var catalog = StoryCatalog.bundled();
    assertTrue(catalog.size() >= 221);
    var settings =
        com.thaalur.quadstock.config.MarketSettings.load(
            Path.of("src/main/resources/config.yml"), ignored -> true);
    for (var i : settings.industries().values()) {
      catalog.validate(i.storyCollection(), i.eventSettings());
      for (String category : StoryCatalog.CATEGORIES)
        assertTrue(
            catalog.stories(i.storyCollection(), category).size()
                >= (category.startsWith("special-") ? 2 : 3));
    }
    catalog.validate("generic", EventSettings.defaults());
  }

  @Test
  public void invalidReferencesPlaceholdersIdsAndMissingCategoriesFail() throws Exception {
    assertThrows(Exception.class, () -> parse(sample().replace("{company}", "{unknown}")));
    assertThrows(Exception.class, () -> parse(sample().replace("id: first", "id: INVALID")));
    assertThrows(Exception.class, () -> parse(sample().replace("positive:", "unknown:")));
    var catalog = parse(sample());
    assertThrows(Exception.class, () -> catalog.validate("missing", EventSettings.defaults()));
    assertThrows(Exception.class, () -> catalog.validate("generic", EventSettings.defaults()));
    assertEquals(
        "Company grows",
        catalog.stories("generic", "positive").getFirst().headline("Company", "TEST"));
    assertEquals(
        "{ticker} grows",
        catalog.stories("generic", "positive").getFirst().headline("{ticker}", "TEST"));
    String entry = sample().substring(sample().indexOf("      - id:"));
    assertThrows(Exception.class, () -> parse(sample() + entry));
    assertThrows(Exception.class, () -> parse(sample() + entry.replace("id: first", "id: second")));
  }

  @Test
  public void invalidEconomicSettingsFailBeforeActivation() throws Exception {
    for (String yaml :
        new String[] {
          "positive-weight: -1",
          "positive-weight: .NaN",
          "unknown: 1",
          "positive-weight: 0\nnegative-weight: 0\nneutral-weight: 0",
          "duration-min-market-hours: 100",
          "special:\n  chance-percent: 101",
          "special:\n  negative-impact-max-percent: 100",
          "special:\n  response-half-life-market-hours: 100",
          "special:\n  max-move-percent-per-market-hour: 0.1"
        }) assertThrows(yaml, Exception.class, () -> EventEngineTest.settings(yaml));
  }
}
