package com.thaalur.quadstock.trading;

import java.io.*;
import java.math.BigDecimal;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit settlement terms. Console changes preserve historical trade terms. */
public record TradingPolicy(
    TradePreview.SettlementMode mode, String provider, int digits, BigDecimal dailyLimit) {
  /**
   * Validates provider identity, supported precision and allowance. Reserve mode needs a positive
   * limit.
   */
  public TradingPolicy {
    Objects.requireNonNull(mode);
    Objects.requireNonNull(provider);
    Objects.requireNonNull(dailyLimit);
    if (provider.isBlank()
        || provider.length() > 128
        || provider.contains("\n")
        || digits < 0
        || digits > 4
        || dailyLimit.signum() < 0
        || dailyLimit.scale() > digits
        || dailyLimit.precision() > 16
        || mode == TradePreview.SettlementMode.RESERVE_BACKED && dailyLimit.signum() == 0)
      throw new IllegalArgumentException("Invalid settlement policy");
  }

  /**
   * Reads the selected policy or returns empty if absent. Invalid saved values fail with
   * IOException.
   */
  public static Optional<TradingPolicy> load(Path dir) throws IOException {
    var state = com.thaalur.quadstock.persistence.StateStore.read(dir, "settlement-policy");
    if (state.isEmpty()) return Optional.empty();
    try {
      var lines = new String(state.get(), StandardCharsets.UTF_8).lines().toList();
      if (lines.size() != 5 || !lines.getFirst().equals("quadstock-policy-v1"))
        throw new IllegalArgumentException();
      return Optional.of(
          new TradingPolicy(
              TradePreview.SettlementMode.valueOf(lines.get(1)),
              lines.get(2),
              Integer.parseInt(lines.get(3)),
              new BigDecimal(lines.get(4))));
    } catch (RuntimeException e) {
      throw new IOException("Invalid settlement policy; trading must remain closed", e);
    }
  }

  /**
   * Atomically installs the first policy in SQLite. Refuses replacement; callers must serialize
   * installation on the server thread.
   */
  public void install(Path dir) throws IOException {
    com.thaalur.quadstock.persistence.StateStore.install(dir, "settlement-policy", bytes());
  }

  /** Atomically saves a validated console selection before desks publish it in memory. */
  public void save(Path dir) throws IOException {
    com.thaalur.quadstock.persistence.StateStore.write(dir, "settlement-policy", bytes());
  }

  private byte[] bytes() {
    byte[] bytes =
        String.join(
                "\n",
                "quadstock-policy-v1",
                mode.name(),
                provider,
                Integer.toString(digits),
                dailyLimit.toPlainString())
            .concat("\n")
            .getBytes(StandardCharsets.UTF_8);
    return bytes;
  }
}
