package com.thaalur.quadstock.config;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class TabSettingsTest {
  private TabSettings parse(String yaml) throws Exception {
    Path file = Files.createTempFile("tab-settings", ".yml");
    try {
      Files.writeString(file, yaml);
      return TabSettings.load(file);
    } finally {
      Files.delete(file);
    }
  }

  @Test
  public void oldInstallAndPackagedDefaultsAgree() throws Exception {
    var defaults = parse("");
    assertEquals(defaults, parse(Files.readString(Path.of("src/main/resources/tab.yml"))));
    assertTrue(defaults.enabled());
    assertTrue(defaults.packUrl().isEmpty());
    assertEquals(17, defaults.iconMaterials().size());
    assertTrue(defaults.iconMaterials().contains("CHEST"));
    Path directory = Files.createTempDirectory("tab-missing");
    assertEquals(defaults, TabSettings.load(directory.resolve("absent.yml")));
    assertFalse(Files.exists(directory.resolve("absent.yml")));
    Files.delete(directory);
  }

  @Test
  public void unsafeAndMisspelledSettingsFail() {
    for (String yaml :
        new String[] {
          "refresh-seconds: .nan",
          "refresh-seconds: 0",
          "rows-per-page: 1.5",
          "rows-per-page: 13",
          "page-seconds: 0",
          "enabled: yesplease",
          "enabeld: true",
          "resource-pack: wrong",
          "resource-pack:\n  url: file:///etc/passwd",
          "resource-pack:\n  url: https://user:secret@example.com/pack.zip",
          "resource-pack:\n  sha1: no",
          "icon-materials: [../chest]",
          "icon-materials: CHEST"
        }) assertThrows(yaml, IOException.class, () -> parse(yaml));
  }

  @Test
  public void customMaterialsAllowMissingHashForTextFallback() throws Exception {
    String custom =
        "resource-pack:\n  url: https://example.com/pack.zip\n  sha1: '"
            + "a".repeat(40)
            + "'\nicon-materials: [AMETHYST_SHARD, CHEST]\n";
    var settings = parse(custom);
    assertTrue(settings.iconMaterials().contains("AMETHYST_SHARD"));
    var fallback = parse(custom.replace("a".repeat(40), ""));
    assertTrue(fallback.packSha1().isEmpty());
    assertEquals(settings.iconMaterials(), fallback.iconMaterials());
    assertFalse(parse("enabled: false").enabled());
  }
}
