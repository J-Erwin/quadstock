package com.thaalur.quadstock.commerce;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Bounded, session-only diagnostic evidence. Never changes prices or money. */
public final class CommerceObservations {

  /**
   * Scalar snapshot of a successful shop event. owner/customer can be null for virtual identities;
   * currency may be null for the default economy. gross/net are diagnostic provider amounts, never
   * inputs to share settlement. Direction is from the shop’s perspective.
   */
  public record Trade(
      long shopId,
      String material,
      int units,
      String shopType,
      UUID owner,
      UUID customer,
      boolean unlimited,
      String currency,
      double gross,
      double net) {
    public UUID buyer() {
      return "BUYING".equals(shopType) ? owner : "SELLING".equals(shopType) ? customer : null;
    }

    public UUID seller() {
      return "BUYING".equals(shopType) ? customer : "SELLING".equals(shopType) ? owner : null;
    }
  }

  /**
   * First matching eligibility outcome. CANDIDATE is diagnostic eligibility, not qualified demand.
   */
  public enum Classification {
    CANDIDATE,
    UNRELATED_ITEM,
    UNLIMITED_SHOP,
    NON_PLAYER,
    SELF_TRADE,
    UNSUPPORTED_DIRECTION,
    INVALID_AMOUNT,
    CUSTOM_CURRENCY
  }

  /** Receipt-time market tick, decoded trade, and eligibility result; retained only in memory. */
  public record Observation(long marketTick, Trade trade, Classification classification) {}

  private final Set<String> items;

  /**
   * Copies the configured union of item mappings. Access recording and reads on the server thread.
   */
  public CommerceObservations(Set<String> items) {
    this.items = Set.copyOf(items);
  }

  private final ArrayDeque<Observation> recent = new ArrayDeque<>();
  private long total;
  private long candidates;

  /**
   * Applies amount, identity, direction, currency and item filters in order. BUYING may be a
   * diagnostic candidate but CommerceSignal accepts SELLING demand only.
   */
  public static Classification classify(Trade trade, Set<String> items) {
    if (trade.units <= 0
        || !Double.isFinite(trade.gross)
        || trade.gross <= 0
        || !Double.isFinite(trade.net)
        || trade.net < 0
        || trade.net > trade.gross) return Classification.INVALID_AMOUNT;
    if (trade.unlimited) return Classification.UNLIMITED_SHOP;
    if (trade.owner == null || trade.customer == null) return Classification.NON_PLAYER;
    if (trade.owner.equals(trade.customer)) return Classification.SELF_TRADE;
    if (!Set.of("BUYING", "SELLING").contains(trade.shopType))
      return Classification.UNSUPPORTED_DIRECTION;
    if (trade.currency != null && !trade.currency.isBlank()) return Classification.CUSTOM_CURRENCY;
    if (!items.contains(trade.material)) return Classification.UNRELATED_ITEM;
    return Classification.CANDIDATE;
  }

  /**
   * Appends one classified observation and updates session counters, retaining only the latest 100.
   */
  public void record(long marketTick, Trade trade) {
    Classification classification = classify(trade, items);
    recent.addLast(new Observation(marketTick, trade, classification));
    if (recent.size() > 100) recent.removeFirst();
    total++;
    if (classification == Classification.CANDIDATE) candidates++;
  }

  /** Returns an immutable oldest-to-newest snapshot of recent diagnostics. */
  public List<Observation> recent() {
    return List.copyOf(recent);
  }

  /** Returns decoded trades received in this observer session, including excluded trades. */
  public long total() {
    return total;
  }

  /** Returns diagnostic candidates in this session; this is not accepted capped demand. */
  public long candidates() {
    return candidates;
  }
}
