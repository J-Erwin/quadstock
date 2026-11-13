package com.thaalur.quadstock.trading;

import static com.thaalur.quadstock.trading.HoldingsLedger.*;
import static com.thaalur.quadstock.trading.TradePreview.SettlementMode.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/**
 * Quote and settlement coordinator for one ticker, sharing a ledger with peer desks.
 *
 * <p>Call on the server thread; per-instance synchronization does not serialize peer desks. Quotes
 * are ephemeral, fixed-price offers, invalidated by expiry, a day change or a changed journal entry
 * count. No funds are reserved when quoting.
 *
 * <p>Confirmation persists PREPARED and IN_FLIGHT before exactly one provider call. An uncertain
 * response remains unresolved; recovery never repeats the payment.
 */
public final class TradingDesk {
  /**
   * Economy boundary, called on the server thread. BUY withdraws; SELL deposits. A false response
   * means a definitive rejection. Exceptions mean an uncertain outcome. Implementations must
   * perform at most one payment attempt and must never retry.
   */
  public interface Gateway {
    String identity();

    int digits();

    BigDecimal balance(UUID player);

    boolean pay(UUID player, Side side, BigDecimal amount);
  }

  /**
   * Ephemeral fixed terms for one player and this desk’s ticker. created/expires are market ticks;
   * version is the global journal entry count, and day is the zero-based market day. An offered
   * quantity smaller than requested must be explicitly accepted.
   */
  public record Quote(
      UUID id,
      UUID player,
      Side side,
      int requested,
      int offered,
      BigDecimal unit,
      BigDecimal amount,
      long created,
      long expires,
      int version,
      long day) {}

  public static final long QUOTE_TICKS = 1000;
  private final HoldingsLedger ledger;
  private final Gateway gateway;
  private TradingPolicy policy;
  private final String ticker, market;
  private BigDecimal dailyLimit;
  private long quoteTicks = QUOTE_TICKS;
  private int maxShares = 10000;
  private boolean enabled = true;
  private long shareSupply = Long.MAX_VALUE;
  private long maxSharesPerPlayer = Long.MAX_VALUE;

  /**
   * Applies validated startup settings and clears outstanding quotes. dailyLimit may be null to
   * inherit policy. This must run before accepting player commands, not concurrently.
   */
  public void configure(
      String ticker,
      String market,
      BigDecimal dailyLimit,
      long quoteTicks,
      int maxShares,
      boolean enabled) {
    if (!this.ticker.equals(ticker) || !this.market.equals(market))
      throw new IllegalArgumentException("A trading desk cannot change ticker or market identity");
    this.dailyLimit = dailyLimit;
    this.quoteTicks = quoteTicks;
    this.maxShares = maxShares;
    this.enabled = enabled;
    if (policy != null
        && dailyLimit != null
        && dailyLimit.stripTrailingZeros().scale() > policy.digits())
      throw new IllegalArgumentException(
          ticker + ": daily payout limit exceeds provider precision");
    quotes.clear();
  }

  /** Applies per-industry limits; existing holdings remain intact even above a lowered limit. */
  public synchronized void configureShareLimits(long supply, long playerCap) {
    if (supply < 0 || playerCap < 0) throw new IllegalArgumentException("Negative share limit");
    shareSupply = supply;
    maxSharesPerPlayer = playerCap;
    quotes.clear();
  }

  /** Configured total supply; Long.MAX_VALUE represents unlimited. */
  public long shareSupply() {
    return shareSupply;
  }

  /** Configured per-player holding cap; Long.MAX_VALUE represents unlimited. */
  public long maxSharesPerPlayer() {
    return maxSharesPerPlayer;
  }

  /** Unowned shares, derived from committed SQLite-backed holdings including offline players. */
  public synchronized long availableShares() {
    return Math.max(0, shareSupply - ledger.outstandingShares(ticker));
  }

  /** Remaining purchase capacity before wallet and per-order limits. */
  public synchronized long purchaseCapacity(UUID player) {
    return Math.min(availableShares(), Math.max(0, maxSharesPerPlayer - position(player).shares()));
  }

  private void checkPurchaseCapacity(UUID player, int shares) {
    if (shares > availableShares())
      throw new IllegalArgumentException(
          "Industry supply has only " + availableShares() + " shares available");
    long room = Math.max(0, maxSharesPerPlayer - position(player).shares());
    if (shares > room)
      throw new IllegalArgumentException(
          "Industry holding cap allows you to buy only " + room + " more shares");
  }

  /** Returns the permanent ticker identity used in journal intents. */
  public String ticker() {
    return ticker;
  }

  /** Returns the reserve-sharing market identity for this ticker. */
  public String marketId() {
    return market;
  }

  /**
   * Returns this player’s committed position for this ticker, including closed-position realized
   * profit.
   */
  public HoldingsLedger.Position position(UUID player) {
    return ledger.position(player, ticker);
  }

  /**
   * Returns this player’s journal entries for this ticker, including failed and pending operations.
   */
  public java.util.List<Entry> history(UUID player) {
    return ledger.history(player).stream().filter(e -> e.intent().ticker().equals(ticker)).toList();
  }

  private final Map<UUID, Quote> quotes = new HashMap<>();

  /**
   * Binds the shared ledger and gateway; null policy keeps trading closed. Existing journal entries
   * must agree with a supplied policy’s provider; historical modes may differ.
   */
  public TradingDesk(
      HoldingsLedger ledger, Gateway gateway, TradingPolicy policy, String ticker, String market) {
    this.ticker = java.util.Objects.requireNonNull(ticker);
    this.market = java.util.Objects.requireNonNull(market);
    this.ledger = ledger;
    this.gateway = gateway;
    this.policy = policy;
    if (policy != null)
      for (Entry e : ledger.entries())
        if (!e.intent().provider().equals(policy.provider()))
          throw new IllegalStateException("Journal and settlement policy disagree");
  }

  /** Returns the selected policy, or null before console selection. */
  public synchronized TradingPolicy policy() {
    return policy;
  }

  /** Validates a selection before persistence, without changing policy or outstanding quotes. */
  public synchronized void validatePolicy(TradingPolicy policy) {
    validatePolicyTerms(policy);
    if (!policy.provider().equals(gateway.identity()) || policy.digits() != gateway.digits())
      throw new IllegalStateException("Economy provider or currency precision changed");
  }

  private void validatePolicyTerms(TradingPolicy policy) {
    Objects.requireNonNull(policy);
    if (ledger.blocked()) throw new IllegalStateException("Settlement is blocked pending recovery");
    if (this.policy != null
        && (!this.policy.provider().equals(policy.provider())
            || this.policy.digits() != policy.digits()))
      throw new IllegalStateException("Economy provider or currency precision cannot be changed");
    for (Entry e : ledger.entries())
      if (!e.intent().provider().equals(policy.provider()))
        throw new IllegalStateException("Journal and settlement policy disagree");
    if (dailyLimit != null && dailyLimit.stripTrailingZeros().scale() > policy.digits())
      throw new IllegalArgumentException(ticker + ": payout limit exceeds currency precision");
  }

  /** Publishes a durably saved, prevalidated policy and invalidates this desk's quotes. */
  public synchronized void activate(TradingPolicy policy) {
    validatePolicyTerms(policy);
    this.policy = policy;
    quotes.clear();
  }

  /** Queries the provider identity; unavailable or changed services may throw. */
  public String provider() {
    return gateway.identity();
  }

  /** Queries supported currency decimal places from the gateway. */
  public int digits() {
    return gateway.digits();
  }

  /**
   * Describes policy, pause and journal state. This does not verify current provider availability.
   */
  public synchronized String status() {
    if (!enabled) return "Trading paused by configuration.";
    if (policy == null) return "Trading closed: administrator must select a settlement policy.";
    if (ledger.blocked()) return "Trading blocked: journal requires recovery.";
    return "Policy: "
        + policy.mode()
        + (policy.mode() == RESERVE_BACKED
            ? " · Reserve " + CurrencyFormat.exact(ledger.reserve(market))
            : " · Sales may create currency");
  }

  private void ready() {
    if (!enabled) throw new IllegalStateException("Trading paused by administrator");
    if (policy == null) throw new IllegalStateException("Settlement policy is not selected");
    if (ledger.blocked()) throw new IllegalStateException("Settlement is blocked pending recovery");
    if (!policy.provider().equals(gateway.identity()) || policy.digits() != gateway.digits())
      throw new IllegalStateException("Economy provider or currency precision changed");
  }

  /**
   * Returns this ticker’s remaining reserve-mode allowance for the day containing tick. Requires a
   * selected policy; buys do not replenish daily allowance.
   */
  public synchronized BigDecimal remaining(long tick) {
    return (dailyLimit == null ? policy.dailyLimit() : dailyLimit)
        .subtract(ledger.paidOut(tick / 24000, ticker))
        .max(BigDecimal.ZERO);
  }

  /** Read-only MAX selection, capped by the configured order limit. Sales still need funding. */
  public synchronized int maximumShares(UUID player, Side side, double mark) {
    ready();
    return side == Side.BUY
        ? (int)
            Math.min(
                purchaseCapacity(player),
                TradePreview.maximumPurchase(
                    mark, policy.digits(), gateway.balance(player), maxShares))
        : (int) Math.min(position(player).shares(), maxShares);
  }

  /**
   * Replaces the player’s previous quote after checking policy, balance or sale funding. Does not
   * move currency or reserve funds. Invalid quantities or unavailable funding fail.
   */
  public synchronized Quote quote(UUID player, Side side, int shares, double mark, long tick) {
    quotes.remove(player);
    ready();
    if (shares > maxShares)
      throw new IllegalArgumentException("Maximum order size is " + maxShares);
    int offered = shares;
    BigDecimal unit, amount;
    if (side == Side.BUY) {
      checkPurchaseCapacity(player, shares);
      var p = TradePreview.purchase(mark, shares, policy.digits(), gateway.balance(player));
      if (!p.affordable())
        throw new IllegalArgumentException(
            "Insufficient wallet balance; shortfall " + p.shortfall());
      unit = p.unitPrice();
      amount = p.total();
    } else {
      var s =
          TradePreview.sale(
              mark,
              shares,
              position(player).shares(),
              policy.digits(),
              policy.mode(),
              ledger.reserve(market),
              remaining(tick));
      if (s.offered() == 0)
        throw new IllegalArgumentException(
            "No shares can be funded by the reserve / ticker allowance");
      offered = s.offered();
      unit = s.unitPrice();
      amount = s.proceeds();
    }
    var q =
        new Quote(
            UUID.randomUUID(),
            player,
            side,
            shares,
            offered,
            unit,
            amount,
            tick,
            Math.addExact(tick, quoteTicks),
            ledger.count(),
            tick / 24000);
    quotes.put(player, q);
    return q;
  }

  /** Discards this player’s outstanding quote without changing holdings or wallet. */
  public synchronized void forget(UUID player) {
    quotes.remove(player);
  }

  /**
   * Consumes a matching quote, rechecks funding and expiry, persists intent and attempt, then
   * invokes the gateway once. Only durable payment success can commit holdings. I/O failure or an
   * uncertain provider exception requires journal inspection before retry.
   */
  public synchronized String confirm(UUID player, UUID id, long tick) throws IOException {
    Quote q = quotes.get(player);
    if (q == null || !q.id.equals(id))
      throw new IllegalStateException("Quote is no longer available; request a new quote");
    quotes.remove(player);
    ready();
    if (tick < q.created
        || tick >= q.expires
        || q.version != ledger.count()
        || q.day != tick / 24000)
      throw new IllegalStateException(
          "Quote expired or market funding changed; request a new quote");
    if (q.side == Side.BUY) checkPurchaseCapacity(player, q.offered);
    if (q.side == Side.BUY && gateway.balance(player).compareTo(q.amount) < 0)
      throw new IllegalArgumentException("Wallet balance changed; insufficient funds");
    if (q.side == Side.SELL) {
      if (position(player).shares() < q.offered)
        throw new IllegalArgumentException("Holdings changed");
      if (policy.mode() == RESERVE_BACKED
          && (ledger.reserve(market).compareTo(q.amount) < 0
              || remaining(tick).compareTo(q.amount) < 0))
        throw new IllegalStateException("Sale funding changed; request a new quote");
    }
    Intent intent =
        new Intent(
            q.id,
            player,
            q.side,
            q.offered,
            q.amount,
            policy.mode(),
            policy.provider(),
            tick,
            ticker,
            market);
    ledger.prepare(intent);
    if (!ledger.markAttempt(q.id, tick))
      throw new IllegalStateException("Payment already attempted; inspect journal");
    final boolean success;
    try {
      success = gateway.pay(player, q.side, q.amount);
    } catch (RuntimeException | LinkageError failure) {
      throw new IllegalStateException(
          "Payment outcome uncertain. Trading is blocked; administrator reconciliation required. ID"
              + " "
              + q.id,
          failure);
    }
    ledger.paymentResult(q.id, success, tick);
    if (!success) return "Economy provider rejected the payment; no holdings changed. ID " + q.id;
    ledger.commit(q.id, tick);
    return q.side
        + " complete: "
        + q.offered
        + " "
        + ticker
        + " for "
        + q.amount.toPlainString()
        + ". Receipt "
        + q.id;
  }

  /**
   * Resolves the globally pending entry without calling the gateway. PREPARED cancels; PAID
   * commits; IN_FLIGHT requires a paid/failed outcome and evidence note. The command adapter, not
   * this method, enforces console-only authorization.
   */
  public synchronized String recover(long tick, String outcome, String note) throws IOException {
    Entry e = ledger.pending().orElseThrow(() -> new IllegalStateException("No pending operation"));
    tick = Math.max(tick, e.steps().getLast().tick());
    if (e.state() == State.PREPARED) ledger.cancelPrepared(e.intent().id(), tick);
    else if (e.state() == State.PAID) ledger.commit(e.intent().id(), tick);
    else {
      if (!outcome.equals("paid") && !outcome.equals("failed"))
        throw new IllegalArgumentException(
            "Inspect provider records; specify paid or failed and an evidence note");
      ledger.reconcile(e.intent().id(), outcome.equals("paid"), tick, note);
      if (outcome.equals("paid")) ledger.commit(e.intent().id(), tick);
    }
    quotes.clear();
    return "Resolved journal operation "
        + e.intent().id()
        + " without repeating any provider payment.";
  }
}
