package com.thaalur.quadstock.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Opens the product SQLite database. Connections are short-lived and server-thread confined. WAL
 * and FULL synchronous mode make each successful transaction durable before a Vault call. SQL
 * schema versions are explicit; unknown versions fail rather than resetting user data.
 */
public final class Database {
  private Database() {}

  /** Opens quadstock.db in directory and initializes schema version 1 on a fresh database. */
  public static Connection open(Path directory) throws IOException {
    Files.createDirectories(directory);
    Connection connection = null;
    try {
      Class.forName("org.sqlite.JDBC");
      connection =
          DriverManager.getConnection(
              "jdbc:sqlite:" + directory.toAbsolutePath().resolve("quadstock.db"));
      try (var statement = connection.createStatement()) {
        statement.execute("PRAGMA busy_timeout=5000");
        statement.execute("PRAGMA foreign_keys=ON");
        statement.execute("PRAGMA journal_mode=WAL");
        statement.execute("PRAGMA synchronous=FULL");
        int version;
        try (var rows = statement.executeQuery("PRAGMA user_version")) {
          if (!rows.next()) throw new SQLException("Missing schema version");
          version = rows.getInt(1);
        }
        if (version != 0 && version != 1)
          throw new SQLException("Unsupported QuadStock schema version " + version);
        if (version == 0) {
          connection.setAutoCommit(false);
          statement.execute("CREATE TABLE state (name TEXT PRIMARY KEY, value BLOB NOT NULL)");
          statement.execute(
              "CREATE TABLE trades (sequence INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, player"
                  + " TEXT NOT NULL, side TEXT NOT NULL CHECK(side IN ('BUY','SELL')), shares"
                  + " INTEGER NOT NULL CHECK(shares>0), amount TEXT NOT NULL, mode TEXT NOT NULL,"
                  + " provider TEXT NOT NULL, tick INTEGER NOT NULL CHECK(tick>=0), ticker TEXT NOT"
                  + " NULL, market TEXT NOT NULL)");
          statement.execute(
              "CREATE TABLE trade_steps (trade_id TEXT NOT NULL REFERENCES trades(id), ordinal"
                  + " INTEGER NOT NULL, state TEXT NOT NULL CHECK(state IN"
                  + " ('PREPARED','IN_FLIGHT','PAID','COMMITTED','FAILED')), tick INTEGER NOT NULL"
                  + " CHECK(tick>=0), note TEXT NOT NULL, PRIMARY KEY(trade_id,ordinal))");
          statement.execute("CREATE INDEX trades_player_ticker ON trades(player,ticker)");
          statement.execute("PRAGMA user_version=1");
          connection.commit();
          connection.setAutoCommit(true);
        }
      }
      return connection;
    } catch (SQLException | ClassNotFoundException failure) {
      if (connection != null)
        try {
          connection.close();
        } catch (SQLException close) {
          failure.addSuppressed(close);
        }
      throw new IOException("Cannot open QuadStock SQLite database in " + directory, failure);
    }
  }
}
