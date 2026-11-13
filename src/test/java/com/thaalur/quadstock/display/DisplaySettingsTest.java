package com.thaalur.quadstock.display;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

public class DisplaySettingsTest {
  private DisplaySettings parse(String text) throws Exception {
    var y = new YamlConfiguration();
    y.loadFromString(text);
    return DisplaySettings.read(y, n -> Set.of("STONE", "CHEST", "OAK_SIGN").contains(n));
  }

  @Test
  public void defaultsMatchPackagedConfiguration() throws Exception {
    var y = new YamlConfiguration();
    y.load(Path.of("src/main/resources/config.yml").toFile());
    assertEquals(
        new HashSet<>(DisplaySettings.DEFAULTS), DisplaySettings.read(y, n -> true).allowed());
    assertEquals(new HashSet<>(DisplaySettings.DEFAULTS), parse("").allowed());
  }

  @Test
  public void customInteractiveBlocksAreAllowedAndEmptyMeansNone() throws Exception {
    assertEquals(
        Set.of("CHEST"), parse("displays:\n  allowed-anchor-materials: [CHEST]\n").allowed());
    assertTrue(parse("displays:\n  allowed-anchor-materials: []\n").allowed().isEmpty());
  }

  @Test
  public void strictValidationRejectsTyposDuplicatesAndBadLimits() {
    for (String config :
        List.of(
            "displays: false",
            "displays:\n  allowed-anchor-materials: STONE",
            "displays:\n  allowed-anchor-materials: [STONE, STONE]",
            "displays:\n  allowed-anchor-materials: [BOGUS]",
            "displays:\n  allowed-anchor-materials: []\n  view-distance: 100",
            "displays:\n  allowed-anchor-materials: []\n  typo: true"))
      assertThrows(config, IOException.class, () -> parse(config));
  }
}
