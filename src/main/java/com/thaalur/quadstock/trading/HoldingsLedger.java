package com.thaalur.quadstock.trading;

import java.io.*;
import java.math.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/**
 * Shared payment journal. SQLite commits each intent or transition before memory changes. Only
 * COMMITTED entries affect holdings. An unresolved payment blocks all settlement. Callers serialize
 * the whole protocol on the server thread; the exclusive process lock prevents another application
 * writer. Startup replays history once; mutations update only the affected entry and position. No
 * provider calls occur in this class.
 */
public final class HoldingsLedger implements AutoCloseable {
  /** Payment direction: BUY withdraws from the player; SELL deposits to the player. */
  public enum Side {
    BUY,
    SELL
  }

  /**
   * Durable payment protocol. PREPARED may cancel; IN_FLIGHT needs an explicit result; PAID may
   * commit. COMMITTED and FAILED are terminal. Only COMMITTED changes holdings.
   */
  public enum State {
    PREPARED,
    IN_FLIGHT,
    PAID,
    COMMITTED,
    FAILED
  }

  /**
   * Immutable operation identity and monetary terms. amount is the positive total currency amount,
   * shares are whole units and tick is the prepare time. ticker/market are explicit stable IDs.
   */
  public record Intent(
      UUID id,
      UUID player,
      Side side,
      int shares,
      BigDecimal amount,
      TradePreview.SettlementMode mode,
      String provider,
      long tick,
      String ticker,
      String market) {
    public Intent {
      if (ticker == null
          || market == null
          || !ticker.matches("[A-Z][A-Z0-9_]{0,15}")
          || !market.matches("[A-Z][A-Z0-9_]{0,15}"))
        throw new IllegalArgumentException("Invalid ticker/market");
      Objects.requireNonNull(id);
      Objects.requireNonNull(player);
      Objects.requireNonNull(side);
      Objects.requireNonNull(mode);
      Objects.requireNonNull(amount);
      Objects.requireNonNull(provider);
      if (shares < 1
          || shares > 10_000
          || amount.signum() <= 0
          || amount.scale() < 0
          || amount.scale() > 4
          || amount.precision() > 16
          || provider.isBlank()
          || provider.length() > 128
          || tick < 0) throw new IllegalArgumentException("Invalid trade intent");
    }
  }

  /**
   * A durable state transition at a market tick, optionally carrying administrator reconciliation
   * evidence.
   */
  public record Step(State state, long tick, String note) {
    public Step(State state, long tick) {
      this(state, tick, "");
    }
  }

  /**
   * An immutable intent and ordered transition history. Validity is enforced during ledger replay.
   */
  public record Entry(Intent intent, List<Step> steps) {
    public Entry {
      steps = List.copyOf(steps);
    }

    public State state() {
      return steps.getLast().state();
    }

    public boolean terminal() {
      return state() == State.COMMITTED || state() == State.FAILED;
    }
  }

  /**
   * Committed shares, remaining aggregate acquisition cost and lifetime realized profit. Partial
   * sales allocate average cost to eight decimal places with HALF_EVEN rounding; closing a position
   * consumes all residual cost. Values exclude wallet cash.
   */
  public record Position(long shares, BigDecimal cost, BigDecimal realized) {
    public static Position empty() {
      return new Position(0, BigDecimal.ZERO, BigDecimal.ZERO);
    }
  }

  private final com.thaalur.quadstock.persistence.LedgerStore store;
  private final FileChannel lockChannel;
  private final FileLock lock;
  private List<Entry> entries = new ArrayList<>();

  private record Key(UUID player, String ticker) {}

  private Map<Key, Position> positions = new HashMap<>();
  private long holdingsRevision;

  /** Session-local invalidation counter, advanced only after committed holdings are published. */
  public synchronized long holdingsRevision() {
    return holdingsRevision;
  }

  private boolean poisoned, closed;

  private HoldingsLedger(
      com.thaalur.quadstock.persistence.LedgerStore store, FileChannel channel, FileLock lock) {
    this.store = store;
    this.lockChannel = channel;
    this.lock = lock;
  }

  /**
   * Acquires an exclusive file lock and validates/replays existing history, or creates an empty
   * journal. Corrupt or incompatible files fail rather than resetting. Caller must close.
   */
  public static HoldingsLedger open(Path directory) throws IOException {
    Files.createDirectories(directory);
    FileChannel channel =
        FileChannel.open(
            directory.resolve("quadstock.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    FileLock lock;
    try {
      lock = channel.tryLock();
      if (lock == null) throw new IOException("Holdings journal already open");
    } catch (Exception e) {
      channel.close();
      throw new IOException("Cannot lock holdings journal", e);
    }
    HoldingsLedger ledger =
        new HoldingsLedger(
            new com.thaalur.quadstock.persistence.SqliteLedgerStore(directory), channel, lock);
    try {
      ledger.entries = new ArrayList<>(ledger.store.load());
      ledger.positions = replay(ledger.entries);
      return ledger;
    } catch (Exception e) {
      ledger.close();
      throw new IOException("Cannot open holdings journal; refusing reset", e);
    }
  }

  /** Returns committed holdings and profit, or an empty position if there is no history. */
  public synchronized Position position(UUID player, String ticker) {
    return positions.getOrDefault(new Key(player, ticker), Position.empty());
  }

  /** Total committed shares held by all players for a ticker, saturated at the long limit. */
  public synchronized long outstandingShares(String ticker) {
    long total = 0;
    for (var entry : positions.entrySet()) {
      if (!entry.getKey().ticker().equals(ticker)) continue;
      long shares = entry.getValue().shares();
      if (shares > Long.MAX_VALUE - total) return Long.MAX_VALUE;
      total += shares;
    }
    return total;
  }

  /**
   * Returns immutable entries for a player across all tickers, including pending and failed
   * operations.
   */
  public synchronized List<Entry> history(UUID player) {
    return entries.stream().filter(e -> e.intent.player.equals(player)).toList();
  }

  /** Returns the single nonterminal operation, if any; it blocks all ticker settlement. */
  public synchronized Optional<Entry> pending() {
    return entries.stream().filter(e -> !e.terminal()).findFirst();
  }

  /** Returns an immutable snapshot of all journal entries in durable order. */
  public synchronized List<Entry> entries() {
    return List.copyOf(entries);
  }

  /** Returns the last transition tick, or zero for an empty journal; used for calendar catch-up. */
  public synchronized long latestTick() {
    return entries.isEmpty() ? 0 : entries.getLast().steps.getLast().tick;
  }

  /**
   * Sums committed reserve-backed purchases minus sales in this market. This is journal accounting,
   * not an economy-provider bank account; shop transactions do not contribute.
   */
  public synchronized BigDecimal reserve(String market) {
    BigDecimal funds = BigDecimal.ZERO;
    for (Entry e : entries)
      if (e.state() == State.COMMITTED
          && e.intent.market.equals(market)
          && e.intent.mode == TradePreview.SettlementMode.RESERVE_BACKED)
        funds = funds.add(e.intent.side == Side.BUY ? e.intent.amount : e.intent.amount.negate());
    return funds;
  }

  /**
   * Sums committed reserve-backed sale totals for a ticker using intent day, not wall-clock date.
   */
  public synchronized BigDecimal paidOut(long day, String ticker) {
    BigDecimal result = BigDecimal.ZERO;
    for (Entry e : entries)
      if (e.state() == State.COMMITTED
          && e.intent.side == Side.SELL
          && e.intent.mode == TradePreview.SettlementMode.RESERVE_BACKED
          && e.intent.ticker.equals(ticker)
          && e.intent.tick / 24000 == day) result = result.add(e.intent.amount);
    return result;
  }

  /**
   * Records an operator-verified result for IN_FLIGHT only. Requires a nonblank evidence note of at
   * most 512 characters. Never checks or calls the economy provider itself.
   */
  public synchronized void reconcile(UUID id, boolean paid, long tick, String evidence)
      throws IOException {
    writable();
    Entry e = find(id);
    if (e.state() != State.IN_FLIGHT
        || evidence == null
        || evidence.isBlank()
        || evidence.length() > 512)
      throw new IllegalArgumentException("An IN_FLIGHT operation and evidence note are required");
    transition(e, paid ? State.PAID : State.FAILED, tick, "CONSOLE RECONCILIATION: " + evidence);
  }

  /** Returns total intents, including failed operations; quote versions use this count. */
  public synchronized int count() {
    return entries.size();
  }

  /** Returns true when closed, poisoned by a failed write, or holding an unresolved operation. */
  public synchronized boolean blocked() {
    return poisoned || closed || pending().isPresent();
  }

  /** Persist this intent before any provider invocation. Duplicate IDs must match exactly. */
  public synchronized Entry prepare(Intent intent) throws IOException {
    writable();
    for (Entry e : entries)
      if (e.intent.id.equals(intent.id)) {
        if (!e.intent.equals(intent))
          throw new IllegalArgumentException("Transaction ID reused with different terms");
        return e;
      }
    if (pending().isPresent())
      throw new IllegalStateException("An unsettled operation blocks this market");
    if (!entries.isEmpty() && intent.tick < entries.getLast().steps.getLast().tick)
      throw new IllegalArgumentException("Cannot backdate intent");
    checkHoldings(intent, positions);
    Entry result = new Entry(intent, List.of(new Step(State.PREPARED, intent.tick)));
    try {
      store.append(result);
    } catch (IOException failure) {
      poisoned = true;
      throw failure;
    }
    entries.add(result);
    return result;
  }

  /** True only once: caller may invoke the provider only after this returns true. */
  public synchronized boolean markAttempt(UUID id, long tick) throws IOException {
    writable();
    Entry e = find(id);
    if (e.state() != State.PREPARED) return false;
    transition(e, State.IN_FLIGHT, tick);
    return true;
  }

  /**
   * Only call for an explicit provider response. Exceptions/timeouts leave IN_FLIGHT unresolved.
   */
  public synchronized void paymentResult(UUID id, boolean success, long tick) throws IOException {
    writable();
    Entry e = find(id);
    State target = success ? State.PAID : State.FAILED;
    if (e.state() == target || success && e.state() == State.COMMITTED) return;
    if (e.state() != State.IN_FLIGHT)
      throw new IllegalStateException("No matching in-flight payment");
    transition(e, target, tick);
  }

  /**
   * Marks a never-attempted operation failed; repeated cancellation is harmless. An attempted
   * payment cannot be cancelled through this method.
   */
  public synchronized void cancelPrepared(UUID id, long tick) throws IOException {
    writable();
    Entry e = find(id);
    if (e.state() == State.FAILED) return;
    if (e.state() != State.PREPARED)
      throw new IllegalStateException("Cannot cancel an attempted payment");
    transition(e, State.FAILED, tick);
  }

  /** Safe to repeat after a confirmed PAID record; never invokes the provider. */
  public synchronized void commit(UUID id, long tick) throws IOException {
    writable();
    Entry e = find(id);
    if (e.state() == State.COMMITTED) return;
    if (e.state() != State.PAID) throw new IllegalStateException("Payment not durably confirmed");
    transition(e, State.COMMITTED, tick);
  }

  private Entry find(UUID id) {
    return entries.stream().filter(e -> e.intent.id.equals(id)).findFirst().orElseThrow();
  }

  private void transition(Entry entry, State state, long tick) throws IOException {
    transition(entry, state, tick, "");
  }

  private void transition(Entry entry, State state, long tick, String note) throws IOException {
    if (tick < entry.steps.getLast().tick)
      throw new IllegalArgumentException("Cannot backdate transition");
    var steps = new ArrayList<>(entry.steps);
    steps.add(new Step(state, tick, note));
    Entry updated = new Entry(entry.intent, steps);
    Position position = state == State.COMMITTED ? apply(entry.intent, positions) : null;
    try {
      store.transition(entry, updated);
    } catch (IOException failure) {
      poisoned = true;
      throw failure;
    }
    entries.set(entries.indexOf(entry), updated);
    if (position != null) {
      positions.put(new Key(entry.intent.player, entry.intent.ticker), position);
      holdingsRevision++;
    }
  }

  private static void checkHoldings(Intent i, Map<Key, Position> positions) {
    Position p = positions.getOrDefault(new Key(i.player, i.ticker), Position.empty());
    if (i.side == Side.SELL && p.shares < i.shares)
      throw new IllegalArgumentException("Insufficient real holdings");
    if (i.side == Side.BUY) Math.addExact(p.shares, i.shares);
  }

  private static Map<Key, Position> replay(List<Entry> list) {
    Map<Key, Position> positions = new HashMap<>();
    Set<UUID> ids = new HashSet<>();
    boolean pending = false;
    long previous = 0;
    Map<String, String> assignments = new HashMap<>();
    for (Entry e : list) {
      String assigned = assignments.putIfAbsent(e.intent.ticker, e.intent.market);
      if (assigned != null && !assigned.equals(e.intent.market))
        throw new IllegalArgumentException("Ticker changed market in journal");
      if (pending
          || !ids.add(e.intent.id)
          || e.intent.tick < previous
          || e.steps.isEmpty()
          || e.steps.getFirst().state != State.PREPARED
          || e.steps.getFirst().tick != e.intent.tick)
        throw new IllegalArgumentException("Invalid journal ordering");
      State last = State.PREPARED;
      previous = e.intent.tick;
      for (int n = 1; n < e.steps.size(); n++) {
        Step step = e.steps.get(n);
        boolean valid =
            last == State.PREPARED && (step.state == State.IN_FLIGHT || step.state == State.FAILED)
                || last == State.IN_FLIGHT
                    && (step.state == State.PAID || step.state == State.FAILED)
                || last == State.PAID && step.state == State.COMMITTED;
        if (!valid || step.tick < previous)
          throw new IllegalArgumentException("Invalid journal transition");
        previous = step.tick;
        last = step.state;
      }
      checkHoldings(e.intent, positions);
      if (e.state() == State.COMMITTED) {
        positions.put(new Key(e.intent.player, e.intent.ticker), apply(e.intent, positions));
      }
      pending = !e.terminal();
    }
    return positions;
  }

  private static Position apply(Intent i, Map<Key, Position> positions) {
    Position p = positions.getOrDefault(new Key(i.player, i.ticker), Position.empty());
    checkHoldings(i, positions);
    if (i.side == Side.BUY)
      return new Position(Math.addExact(p.shares, i.shares), p.cost.add(i.amount), p.realized);
    BigDecimal allocated =
        i.shares == p.shares
            ? p.cost
            : p.cost
                .multiply(BigDecimal.valueOf(i.shares))
                .divide(BigDecimal.valueOf(p.shares), 8, RoundingMode.HALF_EVEN);
    return new Position(
        p.shares - i.shares,
        p.cost.subtract(allocated),
        p.realized.add(i.amount.subtract(allocated)));
  }

  private void writable() {
    if (poisoned || closed)
      throw new IllegalStateException("Journal unavailable; reopen/reconcile before proceeding");
  }

  /** Releases the exclusive application lock; committed SQLite transactions are already durable. */
  public synchronized void close() throws IOException {
    if (closed) return;
    closed = true;
    try {
      lock.release();
    } finally {
      lockChannel.close();
    }
  }
}
