package com.thaalur.quadstock.persistence;

import com.thaalur.quadstock.trading.HoldingsLedger.*;
import com.thaalur.quadstock.trading.TradePreview.SettlementMode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Normalized append-only intents and state transitions; decimal money is stored as exact text. */
public final class SqliteLedgerStore implements LedgerStore {
  private final Path directory;

  public SqliteLedgerStore(Path directory) {
    this.directory = directory;
  }

  @Override
  public List<Entry> load() throws IOException {
    try (var connection = Database.open(directory);
        var query = connection.createStatement();
        var rows = query.executeQuery("SELECT * FROM trades ORDER BY sequence")) {
      var entries = new ArrayList<Entry>();
      while (rows.next()) {
        var intent =
            new Intent(
                UUID.fromString(rows.getString("id")),
                UUID.fromString(rows.getString("player")),
                Side.valueOf(rows.getString("side")),
                rows.getInt("shares"),
                new BigDecimal(rows.getString("amount")),
                SettlementMode.valueOf(rows.getString("mode")),
                rows.getString("provider"),
                rows.getLong("tick"),
                rows.getString("ticker"),
                rows.getString("market"));
        var steps = new ArrayList<Step>();
        try (var querySteps =
            connection.prepareStatement(
                "SELECT * FROM trade_steps WHERE trade_id=? ORDER BY ordinal")) {
          querySteps.setString(1, intent.id().toString());
          try (var results = querySteps.executeQuery()) {
            while (results.next()) {
              if (results.getInt("ordinal") != steps.size())
                throw new SQLException("Non-contiguous journal steps");
              steps.add(
                  new Step(
                      State.valueOf(results.getString("state")),
                      results.getLong("tick"),
                      results.getString("note")));
            }
          }
        }
        entries.add(new Entry(intent, steps));
      }
      return entries;
    } catch (SQLException | RuntimeException failure) {
      throw new IOException("Cannot read payment journal", failure);
    }
  }

  @Override
  public void append(Entry entry) throws IOException {
    try (var connection = Database.open(directory)) {
      connection.setAutoCommit(false);
      var i = entry.intent();
      try (var insert =
          connection.prepareStatement(
              "INSERT INTO trades(id,player,side,shares,amount,mode,provider,tick,ticker,market)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?)")) {
        insert.setString(1, i.id().toString());
        insert.setString(2, i.player().toString());
        insert.setString(3, i.side().name());
        insert.setInt(4, i.shares());
        insert.setString(5, i.amount().toPlainString());
        insert.setString(6, i.mode().name());
        insert.setString(7, i.provider());
        insert.setLong(8, i.tick());
        insert.setString(9, i.ticker());
        insert.setString(10, i.market());
        insert.executeUpdate();
      }
      insertStep(connection, entry.intent().id(), 0, entry.steps().getFirst());
      connection.commit();
    } catch (SQLException failure) {
      throw new IOException("Cannot prepare payment", failure);
    }
  }

  @Override
  public void transition(Entry expected, Entry updated) throws IOException {
    try (var connection = Database.open(directory)) {
      connection.setAutoCommit(false);
      try (var query =
          connection.prepareStatement(
              "SELECT ordinal,state,tick,note FROM trade_steps WHERE trade_id=? ORDER BY ordinal"
                  + " DESC LIMIT 1")) {
        query.setString(1, expected.intent().id().toString());
        try (var rows = query.executeQuery()) {
          var last = expected.steps().getLast();
          if (!rows.next()
              || rows.getInt(1) != expected.steps().size() - 1
              || !rows.getString(2).equals(last.state().name())
              || rows.getLong(3) != last.tick()
              || !rows.getString(4).equals(last.note()))
            throw new SQLException("Journal state changed outside this writer");
        }
      }
      insertStep(
          connection, expected.intent().id(), expected.steps().size(), updated.steps().getLast());
      connection.commit();
    } catch (SQLException failure) {
      throw new IOException("Cannot record payment transition", failure);
    }
  }

  private static void insertStep(Connection connection, UUID id, int ordinal, Step step)
      throws SQLException {
    try (var insert =
        connection.prepareStatement(
            "INSERT INTO trade_steps(trade_id,ordinal,state,tick,note) VALUES(?,?,?,?,?)")) {
      insert.setString(1, id.toString());
      insert.setInt(2, ordinal);
      insert.setString(3, step.state().name());
      insert.setLong(4, step.tick());
      insert.setString(5, step.note());
      insert.executeUpdate();
    }
  }
}
