package com.thaalur.quadstock.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-thread tokens make all buttons of a dialog share one consumable response. */
final class DialogSessions {
  private final Map<UUID, UUID> active = new HashMap<>();

  UUID begin(UUID player) {
    UUID token = UUID.randomUUID();
    active.put(player, token);
    return token;
  }

  boolean consume(UUID player, UUID token) {
    return active.remove(player, token);
  }

  boolean forget(UUID player) {
    return active.remove(player) != null;
  }
}
