package com.thaalur.quadstock.notifications;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SubscriptionsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void explicitOptInAndOutSurviveReload() throws Exception {
    var file = temporary.getRoot().toPath();
    UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    var subscriptions = Subscriptions.load(file);
    assertFalse(subscriptions.contains(first));
    subscriptions.set(first, true);
    subscriptions.set(second, true);
    subscriptions = Subscriptions.load(file);
    assertTrue(subscriptions.contains(first));
    assertTrue(subscriptions.contains(second));
    subscriptions.set(first, false);
    subscriptions = Subscriptions.load(file);
    assertFalse(subscriptions.contains(first));
    assertTrue(subscriptions.contains(second));
  }

  @Test
  public void corruptionIsRejectedAndPreserved() throws Exception {
    var file = temporary.getRoot().toPath();
    com.thaalur.quadstock.persistence.StateStore.write(
        file, "subscriptions", "quadstock-subscriptions-v1\ninvalid\n".getBytes());
    assertThrows(IOException.class, () -> Subscriptions.load(file));
    assertTrue(
        new String(
                com.thaalur.quadstock.persistence.StateStore.read(file, "subscriptions")
                    .orElseThrow())
            .contains("invalid"));
  }

  @Test
  public void failedSaveDoesNotEnableSubscription() throws Exception {
    var blocking = temporary.getRoot().toPath().resolve("parent");
    var subscriptions = Subscriptions.load(blocking);
    Files.writeString(blocking, "not a directory");
    UUID player = UUID.randomUUID();
    assertThrows(IOException.class, () -> subscriptions.set(player, true));
    assertFalse(subscriptions.contains(player));
  }
}
