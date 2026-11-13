package com.thaalur.quadstock.persistence;

import com.thaalur.quadstock.trading.HoldingsLedger.Entry;
import java.io.IOException;
import java.util.List;

/** Durable boundary for the payment journal; no implementation may invoke an economy provider. */
public interface LedgerStore {
  /** Returns complete entries in durable sequence order for startup validation and replay. */
  List<Entry> load() throws IOException;

  /** Inserts a new intent and PREPARED step in one database transaction. */
  void append(Entry entry) throws IOException;

  /** Adds exactly one transition, requiring the persisted previous step to match expected. */
  void transition(Entry expected, Entry updated) throws IOException;
}
