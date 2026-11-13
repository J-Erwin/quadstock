package com.thaalur.quadstock.notifications;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Explicit opt-in preferences. Failed writes leave the prior in-memory preference intact. */
public final class Subscriptions {
  private static final String HEADER = "quadstock-subscriptions-v1";
  private final Path directory;
  private Set<UUID> players;

  private Subscriptions(Path directory, Set<UUID> players) {
    this.directory = directory.toAbsolutePath();
    this.players = players;
  }

  /**
   * Loads strict UUID preferences; missing checkpoints mean nobody opted in. Malformed headers,
   * UUIDs and duplicates fail instead of silently clearing subscriptions.
   */
  public static Subscriptions load(Path directory) throws IOException {
    Set<UUID> players = new HashSet<>();
    var state = com.thaalur.quadstock.persistence.StateStore.read(directory, "subscriptions");
    if (state.isPresent()) {
      var lines = new String(state.get(), StandardCharsets.UTF_8).lines().toList();
      if (lines.isEmpty() || !HEADER.equals(lines.getFirst())) {
        throw new IOException("Invalid subscription checkpoint: " + directory);
      }
      try {
        for (String line : lines.subList(1, lines.size())) {
          UUID id = UUID.fromString(line);
          if (!id.toString().equals(line) || !players.add(id)) {
            throw new IllegalArgumentException("Invalid or duplicate subscriber");
          }
        }
      } catch (IllegalArgumentException e) {
        throw new IOException("Invalid subscriber entry in " + directory, e);
      }
    }
    return new Subscriptions(directory, players);
  }

  /**
   * Returns whether the player opted in. Access on the server thread with other preference
   * operations.
   */
  public boolean contains(UUID player) {
    return players.contains(player);
  }

  /**
   * Commits the SQLite preference checkpoint before publishing the new in-memory set. IOException
   * leaves the old memory state intact.
   */
  public void set(UUID player, boolean subscribed) throws IOException {
    Set<UUID> next = new HashSet<>(players);
    if (subscribed) next.add(player);
    else next.remove(player);
    String body =
        HEADER + "\n" + String.join("\n", next.stream().map(UUID::toString).sorted().toList());
    com.thaalur.quadstock.persistence.StateStore.write(
        directory, "subscriptions", body.getBytes(StandardCharsets.UTF_8));
    players = next;
  }
}
