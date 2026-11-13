package com.thaalur.quadstock.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;

/**
 * SQLite-backed named checkpoints. Values are private versioned payloads, not external API
 * contracts.
 */
public final class StateStore {
  private StateStore() {}

  /** Reads a checkpoint without synthesizing missing state. */
  public static Optional<byte[]> read(Path directory, String name) throws IOException {
    if (!java.nio.file.Files.exists(directory.resolve("quadstock.db"))) return Optional.empty();
    try (var connection = Database.open(directory);
        var query = connection.prepareStatement("SELECT value FROM state WHERE name=?")) {
      query.setString(1, name);
      try (var rows = query.executeQuery()) {
        return rows.next() ? Optional.of(rows.getBytes(1)) : Optional.empty();
      }
    } catch (SQLException failure) {
      throw new IOException("Cannot read checkpoint " + name, failure);
    }
  }

  /**
   * Atomically inserts or replaces one checkpoint. Memory owners publish changes only after
   * success.
   */
  public static void write(Path directory, String name, byte[] value) throws IOException {
    try (var connection = Database.open(directory);
        var command =
            connection.prepareStatement(
                "INSERT INTO state(name,value) VALUES(?,?) ON CONFLICT(name) DO UPDATE SET"
                    + " value=excluded.value")) {
      command.setString(1, name);
      command.setBytes(2, value);
      command.executeUpdate();
    } catch (SQLException failure) {
      throw new IOException("Cannot save checkpoint " + name, failure);
    }
  }

  /**
   * Inserts a one-time setting, refusing replacement even if the caller checked existence earlier.
   */
  public static void install(Path directory, String name, byte[] value) throws IOException {
    try (var connection = Database.open(directory);
        var command = connection.prepareStatement("INSERT INTO state(name,value) VALUES(?,?)")) {
      command.setString(1, name);
      command.setBytes(2, value);
      command.executeUpdate();
    } catch (SQLException failure) {
      throw new IOException("Cannot install setting " + name, failure);
    }
  }

  /** Commits a group of checkpoints atomically, or rolls all of them back on failure. */
  public static void writeAll(Path directory, java.util.Map<String, byte[]> values)
      throws IOException {
    try (var connection = Database.open(directory)) {
      connection.setAutoCommit(false);
      try (var command =
          connection.prepareStatement(
              "INSERT INTO state(name,value) VALUES(?,?) ON CONFLICT(name) DO UPDATE SET"
                  + " value=excluded.value")) {
        for (var entry : values.entrySet()) {
          command.setString(1, entry.getKey());
          command.setBytes(2, entry.getValue());
          command.addBatch();
        }
        command.executeBatch();
      }
      connection.commit();
    } catch (SQLException failure) {
      throw new IOException("Cannot commit coordinated checkpoint", failure);
    }
  }
}
