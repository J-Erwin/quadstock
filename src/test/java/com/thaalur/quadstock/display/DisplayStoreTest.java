package com.thaalur.quadstock.display;

import static org.junit.Assert.*;

import com.thaalur.quadstock.persistence.StateStore;
import java.io.IOException;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class DisplayStoreTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  static DisplayDefinition draft() {
    return DisplayDefinition.create(
        new DisplayDefinition.Anchor(UUID.randomUUID(), 1, 64, -2, "STONE", "minecraft:stone"),
        "CUSTOM",
        DisplayDefinition.Surface.FLOATING,
        90);
  }

  @Test
  public void restartKeepsDefinitionAndRemovalObligation() throws Exception {
    var dir = temporary.getRoot().toPath();
    var store = new DisplayStore(dir);
    var draft = draft();
    var saved = draft.saved(Map.of(), false);
    store.put(saved, 0);
    assertEquals(saved, new DisplayStore(dir).all().get(draft.id()));
    var pending = saved.saved(Map.of(), true);
    store.put(pending, 1);
    var restarted = new DisplayStore(dir);
    assertTrue(restarted.all().get(draft.id()).removed());
    restarted.forget(draft.id());
    assertTrue(new DisplayStore(dir).all().isEmpty());
  }

  @Test
  public void rejectsStaleEditorAndDuplicateAnchor() throws Exception {
    var store = new DisplayStore(temporary.getRoot().toPath());
    var first = draft().saved(Map.of(), false);
    store.put(first, 0);
    assertThrows(
        IOException.class, () -> store.put(first.option("type", "NEWS").saved(Map.of(), false), 0));
    var other =
        DisplayDefinition.create(first.anchor(), "OTHER", DisplayDefinition.Surface.SIGN, 0)
            .saved(Map.of(), false);
    assertThrows(IOException.class, () -> store.put(other, 0));
    assertEquals(1, store.all().size());
  }

  @Test
  public void failedCommitDoesNotPublishOrEraseExistingState() throws Exception {
    var dir = temporary.getRoot().toPath();
    var store = new DisplayStore(dir);
    var first = draft().saved(Map.of(), false);
    store.put(first, 0);
    try (var c = com.thaalur.quadstock.persistence.Database.open(dir);
        var sql = c.createStatement()) {
      sql.execute("PRAGMA user_version=999");
    }
    assertThrows(IOException.class, () -> store.put(first.saved(Map.of(), true), 1));
    assertEquals(first, store.all().get(first.id()));
  }

  @Test
  public void invalidPayloadFailsRatherThanResets() throws Exception {
    var dir = temporary.getRoot().toPath();
    StateStore.write(dir, "physical-displays", new byte[] {0, 0, 0, 2, 0, 0, 0, 0});
    assertThrows(IOException.class, () -> new DisplayStore(dir));
    var valid = DisplayStore.encode(Map.of());
    assertThrows(
        IOException.class, () -> DisplayStore.decode(Arrays.copyOf(valid, valid.length + 1)));
  }

  @Test
  public void mirrorsSidesAndRejectsInvalidSettings() {
    var d = draft().option("sides", "both");
    assertTrue(d.side("FRONT"));
    assertTrue(d.side("BACK"));
    assertThrows(IllegalArgumentException.class, () -> d.option("size", "NaN"));
    assertThrows(IllegalArgumentException.class, () -> d.option("scroll-ms", "0"));
    assertThrows(IllegalArgumentException.class, () -> d.option("action", "trade"));
    assertThrows(IllegalArgumentException.class, () -> d.option("ticker", "bad ticker"));
  }

  @Test
  public void supportProtectionSurvivesRestartAndPendingRemovalWithoutLoadingWorld()
      throws Exception {
    var dir = temporary.getRoot().toPath();
    var store = new DisplayStore(dir);
    var d = draft();
    String support = d.anchor().world() + ":0:64:-2";
    var saved = d.saved(Map.of("supports", support), false);
    store.put(saved, 0);
    var restarted = new DisplayStore(dir);
    assertTrue(restarted.all().get(d.id()).protectedLocations().contains(support));
    var removed = saved.saved(saved.originals(), true);
    restarted.put(removed, 1);
    assertTrue(new DisplayStore(dir).all().get(d.id()).protectedLocations().contains(support));
    assertThrows(
        IllegalArgumentException.class,
        () -> d.saved(Map.of("supports", UUID.randomUUID() + ":0:64:-2"), false));
  }
}
