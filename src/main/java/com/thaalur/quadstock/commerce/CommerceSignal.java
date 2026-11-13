package com.thaalur.quadstock.commerce;

import static com.thaalur.quadstock.data.MarketCalendar.*;

import java.io.*;
import java.util.*;

/** Bounded demand evidence; all times are market ticks. Not a fraud detector. */
public final class CommerceSignal {
  public static final double MAX_ADJUSTMENT = .02;
  private static final int MAX_RECORDS = 4096;

  /** Minimum distinct actors and unordered trading pairs in the trailing market day. */
  public record Requirements(int buyers, int sellers, int participants, int pairs) {
    public static final Requirements DEFAULT = new Requirements(3, 2, 4, 3);

    public Requirements {
      if (buyers < 1
          || buyers > 4096
          || sellers < 1
          || sellers > 4096
          || participants < 2
          || participants > 8192
          || pairs < 1
          || pairs > 4096)
        throw new IllegalArgumentException("Invalid commerce diversity requirements");
    }
  }

  private final Requirements requirements;

  private record Evidence(long tick, UUID buyer, UUID seller, int units) {}

  /**
   * Trailing-day diversity and capped units plus the last smoothed fractional adjustment. Qualified
   * means diversity/learning requirements are met, not that a price rise is guaranteed.
   */
  public record Summary(
      int buyers,
      int sellers,
      int participants,
      int pairs,
      int units,
      double weightedUnits,
      boolean learning,
      boolean qualified,
      double adjustment) {
    public String status() {
      return learning
          ? "Learning: first market day"
          : qualified ? "Broad activity observed" : "Insufficient independent activity";
    }

    public String display() {
      return String.format(Locale.US, "%s | commerce outlook %+.3f%%", status(), adjustment * 100);
    }
  }

  private final long started;
  private long evaluated;
  private double adjustment;
  private final ArrayList<Evidence> evidence = new ArrayList<>();

  /**
   * Starts a one-market-day learning period at tick. Runtime access is serialized by its
   * LivingMarket owner.
   */
  public CommerceSignal(long tick) {
    this(tick, Requirements.DEFAULT);
  }

  /** Starts evidence collection using administrator-selected diversity requirements. */
  public CommerceSignal(long tick, Requirements requirements) {
    this.requirements = Objects.requireNonNull(requirements);
    started = evaluated = tick;
  }

  private void prune(long now) {
    evidence.removeIf(e -> now - e.tick >= DAY_TICKS);
  }

  private static boolean samePair(Evidence e, UUID a, UUID b) {
    return e.buyer.equals(a) && e.seller.equals(b) || e.buyer.equals(b) && e.seller.equals(a);
  }

  /** Only completed, eligible retail SELLING trades measure demand. BUYING remains diagnostic. */
  public int record(long tick, CommerceObservations.Trade trade) {
    return record(tick, trade, Set.of("WHEAT", "CARROT", "POTATO", "BEETROOT"));
  }

  /**
   * Accepts mapped SELLING demand after eligibility and trailing-day pair/participant caps. Returns
   * accepted units, possibly zero; does not immediately update the outlook. Rejects ticks older
   * than the last evaluation; callers must supply chronological events.
   */
  public int record(long tick, CommerceObservations.Trade trade, Set<String> items) {
    if (tick < evaluated) throw new IllegalArgumentException("Cannot backdate commerce");
    prune(tick);
    if (CommerceObservations.classify(trade, items) != CommerceObservations.Classification.CANDIDATE
        || !"SELLING".equals(trade.shopType())
        || evidence.size() >= MAX_RECORDS) return 0;
    UUID buyer = trade.buyer(), seller = trade.seller();
    int pair = 0, buyerTotal = 0, sellerTotal = 0;
    for (Evidence e : evidence) {
      if (samePair(e, buyer, seller)) pair += e.units;
      if (e.buyer.equals(buyer) || e.seller.equals(buyer)) buyerTotal += e.units;
      if (e.buyer.equals(seller) || e.seller.equals(seller)) sellerTotal += e.units;
    }
    int units =
        Math.min(trade.units(), Math.min(48 - pair, Math.min(96 - buyerTotal, 96 - sellerTotal)));
    if (units > 0) evidence.add(new Evidence(tick, buyer, seller, units));
    return Math.max(0, units);
  }

  /**
   * Computes trailing-day evidence at now without advancing the smoothed adjustment or mutating
   * state.
   */
  public Summary summary(long now) {
    Set<UUID> buyers = new HashSet<>(), sellers = new HashSet<>(), people = new HashSet<>();
    Set<Set<UUID>> pairs = new HashSet<>();
    int units = 0;
    double weighted = 0;
    for (Evidence e : evidence) {
      long age = now - e.tick;
      if (age < 0 || age >= DAY_TICKS) continue;
      buyers.add(e.buyer);
      sellers.add(e.seller);
      people.add(e.buyer);
      people.add(e.seller);
      pairs.add(Set.of(e.buyer, e.seller));
      units += e.units;
      weighted += e.units * (1 - age / (double) DAY_TICKS);
    }
    boolean learning = now - started < DAY_TICKS;
    boolean qualified =
        !learning
            && buyers.size() >= requirements.buyers()
            && sellers.size() >= requirements.sellers()
            && people.size() >= requirements.participants()
            && pairs.size() >= requirements.pairs();
    return new Summary(
        buyers.size(),
        sellers.size(),
        people.size(),
        pairs.size(),
        units,
        weighted,
        learning,
        qualified,
        adjustment);
  }

  /**
   * Prunes expired evidence and smooths toward qualified demand with a six-hour half-life. Returns
   * a fractional adjustment bounded by MAX_ADJUSTMENT; rejects time reversal.
   */
  public double advance(long now) {
    if (now < evaluated) throw new IllegalArgumentException("Cannot rewind commerce");
    prune(now);
    Summary s = summary(now);
    double target = s.qualified ? MAX_ADJUSTMENT * Math.min(1, s.weightedUnits / 384.0) : 0;
    // Six-market-hour half-life for both buildup and decay.
    double response = 1 - Math.pow(.5, (now - evaluated) / (6.0 * HOUR_TICKS));
    adjustment += (target - adjustment) * response;
    if (adjustment < 1e-9) adjustment = 0;
    evaluated = now;
    return adjustment;
  }

  /**
   * Writes the evidence payload into its owner’s snapshot stream; does not close or flush the
   * stream.
   */
  public void write(DataOutput out) throws IOException {
    out.writeLong(started);
    out.writeLong(evaluated);
    out.writeDouble(adjustment);
    out.writeInt(evidence.size());
    for (Evidence e : evidence) {
      out.writeLong(e.tick);
      out.writeLong(e.buyer.getMostSignificantBits());
      out.writeLong(e.buyer.getLeastSignificantBits());
      out.writeLong(e.seller.getMostSignificantBits());
      out.writeLong(e.seller.getLeastSignificantBits());
      out.writeInt(e.units);
    }
  }

  /**
   * Reads and validates bounded evidence from a snapshot stream against its owner’s market tick.
   */
  public static CommerceSignal read(DataInput in, long marketTick) throws IOException {
    return read(in, marketTick, Requirements.DEFAULT);
  }

  /** Restores evidence and applies current configuration to future qualification checks. */
  public static CommerceSignal read(DataInput in, long marketTick, Requirements requirements)
      throws IOException {
    long start = in.readLong(), evaluated = in.readLong();
    double adjustment = in.readDouble();
    int count = in.readInt();
    if (start < 0
        || start > evaluated
        || evaluated > marketTick
        || !Double.isFinite(adjustment)
        || adjustment < 0
        || adjustment > MAX_ADJUSTMENT
        || count < 0
        || count > MAX_RECORDS) throw new IOException("Invalid commerce state");
    CommerceSignal signal = new CommerceSignal(start, requirements);
    signal.evaluated = evaluated;
    signal.adjustment = adjustment;
    long previous = start;
    for (int i = 0; i < count; i++) {
      long tick = in.readLong();
      UUID buyer = new UUID(in.readLong(), in.readLong()),
          seller = new UUID(in.readLong(), in.readLong());
      int units = in.readInt();
      if (tick < previous || tick > marketTick || units <= 0 || units > 48 || buyer.equals(seller))
        throw new IOException("Invalid commerce evidence");
      signal.evidence.add(new Evidence(tick, buyer, seller, units));
      previous = tick;
    }
    return signal;
  }
}
