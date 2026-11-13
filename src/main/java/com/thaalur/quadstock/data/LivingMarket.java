package com.thaalur.quadstock.data;

import com.thaalur.quadstock.commerce.CommerceObservations;
import com.thaalur.quadstock.commerce.CommerceSignal;
import com.thaalur.quadstock.events.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Recorded simulation for a single configured industry; never transfers money or items.
 *
 * <p>Uses market ticks, deterministic persisted random state and five-market-minute observations.
 * Historical gaps stay empty. Prices are bounded to 1–1,000,000 and each sample movement is capped
 * by elapsed time. Eligible commerce adjusts the reference outlook, not an immediately executable
 * price or reserve balance.
 *
 * <p>Runtime access is on the server thread; state methods are synchronized. Checkpoints include
 * observations, events and commerce evidence. Save the calendar before markets.
 */
public final class LivingMarket {
  private static final int MAGIC = 0x51534C34;
  private static final long HOUR = MarketCalendar.HOUR_TICKS;
  public static final int DEFAULT_RETENTION_MONTHS = 12;
  public static final int MAX_RETENTION_MONTHS = 12;
  // Twelve months hold at most 96,769 five-minute samples, including the first boundary.
  private static final int MAX_SAVED_OBSERVATIONS = 100_000;
  private final long retentionTicks;
  private final Path directory;
  private final String checkpoint;
  private final ArrayList<PriceObservation> observations = new ArrayList<>();
  private final ArrayList<IndustryEvent> events = new ArrayList<>();
  private long tick, nextObservation, nextEvent, randomState;
  private double price = 100;
  private CommerceSignal commerce;
  private CommerceSignal.Requirements commerceRequirements;
  private double referencePrice = 100, volatility = 1, influenceScale = 1;
  private boolean eventsEnabled = true;
  private String industryName, ticker, storyCollection;
  private EventSettings eventSettings;
  private StoryCatalog stories;
  private EventEngine eventEngine;
  private Set<String> items;

  /** A recorded mark at a market tick; it is not an executed trade price. */
  public record PriceObservation(long tick, double price) {}

  /**
   * Simulated conditions over a start-inclusive, end-exclusive tick interval. Adjustment is a
   * fraction, not percent points.
   */
  public record IndustryEvent(
      long startTick,
      long endTick,
      String title,
      String description,
      double outlookAdjustment,
      String category,
      String collection,
      String storyId,
      double responseHalfLifeHours,
      double maxMovePercent) {
    public boolean special() {
      return category.startsWith("special-");
    }

    public String direction() {
      return category.replace("special-", "");
    }

    public String label() {
      return (special() ? "SPECIAL · " : "") + direction().toUpperCase(Locale.ROOT);
    }
  }

  /**
   * A chart interval and its actual observations. Empty prices indicate missing history, not zero
   * value.
   */
  public record HistoryBin(
      long startTick, long endTick, Optional<PriceBar> prices, int observations) {}

  private LivingMarket(Path directory, String ticker, int retentionMonths) {
    if (retentionMonths < 1 || retentionMonths > MAX_RETENTION_MONTHS)
      throw new IllegalArgumentException("History retention must be 1–12 market months");
    retentionTicks = retentionMonths * MarketCalendar.MONTH_TICKS;
    this.directory = directory.toAbsolutePath();
    this.checkpoint = "market/" + ticker;
  }

  /**
   * Loads and validates a snapshot, then advances to the shared calendar. Missing checkpoints
   * create a new simulation at the configured reference price. Settings change future dynamics
   * without rewriting retained observations; influenceScale multiplies the normalized 2% signal.
   */
  public static LivingMarket load(
      Path directory,
      long currentTicks,
      com.thaalur.quadstock.config.MarketSettings.Industry settings,
      double influenceScale)
      throws IOException {
    return load(
        directory, currentTicks, settings, influenceScale, CommerceSignal.Requirements.DEFAULT);
  }

  /** Loads persisted evidence using current diversity rules and the configured influence cap. */
  public static LivingMarket load(
      Path directory,
      long currentTicks,
      com.thaalur.quadstock.config.MarketSettings.Industry settings,
      double influenceScale,
      CommerceSignal.Requirements requirements)
      throws IOException {
    return load(
        directory, currentTicks, settings, influenceScale, requirements, StoryCatalog.bundled());
  }

  /**
   * Restores market state against a validated narrative catalog and resolved per-industry policy.
   */
  public static LivingMarket load(
      Path directory,
      long currentTicks,
      com.thaalur.quadstock.config.MarketSettings.Industry settings,
      double influenceScale,
      CommerceSignal.Requirements requirements,
      StoryCatalog stories)
      throws IOException {
    return load(
        directory,
        currentTicks,
        settings,
        influenceScale,
        requirements,
        stories,
        DEFAULT_RETENTION_MONTHS);
  }

  /**
   * Applies a bounded retention policy to the existing snapshot format. Increasing retention keeps
   * every available sample; it cannot reconstruct samples pruned by a previous policy.
   */
  public static LivingMarket load(
      Path directory,
      long currentTicks,
      com.thaalur.quadstock.config.MarketSettings.Industry settings,
      double influenceScale,
      CommerceSignal.Requirements requirements,
      StoryCatalog stories,
      int retentionMonths)
      throws IOException {
    stories.validate(settings.storyCollection(), settings.eventSettings());
    if (!Double.isFinite(influenceScale) || influenceScale < 0 || influenceScale > 50)
      throw new IllegalArgumentException("Commerce scale must be between 0 and 50");
    if (currentTicks < 0) throw new IllegalArgumentException("Negative calendar");
    java.util.Objects.requireNonNull(settings, "Industry settings are required");
    LivingMarket market = new LivingMarket(directory, settings.ticker(), retentionMonths);
    market.ticker = settings.ticker();
    market.storyCollection = settings.storyCollection();
    market.eventSettings = settings.eventSettings();
    market.stories = stories;
    market.eventEngine = new EventEngine(settings.ticker());
    market.referencePrice = settings.referencePrice();
    market.price = settings.referencePrice();
    market.volatility = settings.volatility();
    market.eventsEnabled = settings.eventsEnabled();
    market.industryName = settings.name();
    market.items = settings.items();
    market.influenceScale = influenceScale;
    market.commerceRequirements = java.util.Objects.requireNonNull(requirements);
    var saved = com.thaalur.quadstock.persistence.StateStore.read(directory, market.checkpoint);
    if (saved.isEmpty()) {
      market.tick = currentTicks;
      market.commerce = new CommerceSignal(currentTicks, requirements);
      market.nextObservation = nextSampleAfter(currentTicks);
      market.nextEvent = Math.addExact(currentTicks, 6 * HOUR);
      market.randomState = 0x514D41524B4554L ^ settings.ticker().hashCode();
      market.observations.add(new PriceObservation(currentTicks, market.price));
      return market;
    }
    try {
      byte[] data = saved.get();
      if (data.length < 12 || data.length > 10_000_000)
        throw new IOException("Invalid snapshot length");
      int bodyLength = data.length - Long.BYTES;
      CRC32 crc = new CRC32();
      crc.update(data, 0, bodyLength);
      if (ByteBuffer.wrap(data, bodyLength, Long.BYTES).getLong() != crc.getValue())
        throw new IOException("Snapshot checksum mismatch");
      try (DataInputStream input =
          new DataInputStream(new ByteArrayInputStream(data, 0, bodyLength))) {
        int magic = input.readInt();
        if (magic != MAGIC)
          throw new IOException(
              "Unsupported market snapshot version; see admin guide for the explicit local test"
                  + " reset procedure. Saved data was not changed.");
        market.tick = input.readLong();
        market.nextObservation = input.readLong();
        market.nextEvent = input.readLong();
        market.randomState = input.readLong();
        market.price = input.readDouble();
        int count = boundedCount(input.readInt(), MAX_SAVED_OBSERVATIONS);
        for (int i = 0; i < count; i++)
          market.observations.add(new PriceObservation(input.readLong(), input.readDouble()));
        count = boundedCount(input.readInt(), 512);
        for (int i = 0; i < count; i++)
          market.events.add(
              new IndustryEvent(
                  input.readLong(),
                  input.readLong(),
                  input.readUTF(),
                  input.readUTF(),
                  input.readDouble(),
                  input.readUTF(),
                  input.readUTF(),
                  input.readUTF(),
                  input.readDouble(),
                  input.readDouble()));
        market.eventEngine = EventEngine.read(input);
        market.commerce = CommerceSignal.read(input, market.tick, requirements);
        if (input.available() != 0) throw new IOException("Trailing snapshot data");
      }
      market.validate(currentTicks);
      market.advanceTo(currentTicks);
      return market;
    } catch (RuntimeException e) {
      throw new IOException("Invalid living market snapshot: " + market.checkpoint, e);
    }
  }

  private static int boundedCount(int count, int maximum) throws IOException {
    if (count < 0 || count > maximum) throw new IOException("Invalid snapshot count");
    return count;
  }

  private void validate(long calendar) throws IOException {
    if (tick < 0
        || tick > calendar
        || nextObservation <= tick
        || nextObservation - tick > HOUR
        || randomState == 0
        || !validPrice(price)
        || observations.isEmpty())
      throw new IOException("Invalid market state or market ahead of saved calendar");
    long previous = -1;
    for (PriceObservation point : observations) {
      if (point.tick <= previous || point.tick > tick || !validPrice(point.price))
        throw new IOException("Invalid observations");
      previous = point.tick;
    }
    if (observations.getLast().price != price
        || nextEvent <= observations.getLast().tick
        || nextObservation != nextSampleAfter(tick))
      throw new IOException("Observation does not match current state");
    previous = -1;
    for (IndustryEvent event : events) {
      if (event.startTick <= previous
          || event.startTick > tick
          || event.endTick <= event.startTick
          || event.title.isBlank()
          || event.description.isBlank()
          || !Double.isFinite(event.outlookAdjustment)
          || Math.abs(event.outlookAdjustment) > .9
          || !StoryCatalog.CATEGORIES.contains(event.category)
          || !event.collection.matches("[a-z][a-z0-9_-]{0,63}")
          || !event.storyId.matches("[a-z][a-z0-9_-]{0,63}")
          || event.title.length() > 1024
          || event.description.length() > 4096
          || !Double.isFinite(event.responseHalfLifeHours)
          || event.responseHalfLifeHours < .01
          || event.responseHalfLifeHours > 8760
          || !Double.isFinite(event.maxMovePercent)
          || event.maxMovePercent < .01
          || event.maxMovePercent > 100
          || (event.direction().equals("neutral") && event.outlookAdjustment != 0)
          || (event.direction().equals("negative") && event.outlookAdjustment > 0)
          || (event.direction().equals("positive") && event.outlookAdjustment < 0))
        throw new IOException("Invalid industry event");
      previous = event.startTick;
    }
  }

  private static boolean validPrice(double value) {
    return Double.isFinite(value) && value >= 1 && value <= 1000000;
  }

  /** Twelve five-minute boundaries per 1,000-tick hour; 83/84 ticks avoids rounding drift. */
  public static long nextSampleAfter(long tick) {
    long hourStart = tick - tick % HOUR;
    for (int slot = 1; slot <= 12; slot++) {
      long boundary = Math.addExact(hourStart, slot * HOUR / 12);
      if (boundary > tick) return boundary;
    }
    throw new IllegalArgumentException("No subsequent sample boundary");
  }

  /**
   * Advances monotonically, recording every crossed sample boundary and pruning old prices. Large
   * catch-up spans execute synchronously. Rewinding is rejected.
   */
  public synchronized void advanceTo(long targetTick) {
    if (targetTick < tick) throw new IllegalArgumentException("Cannot rewind market");
    while (nextObservation <= targetTick) {
      tick = nextObservation;
      if (tick >= nextEvent) beginEvent();
      double adjustment =
          (eventsEnabled ? activeEvent() : Optional.<IndustryEvent>empty())
              .map(IndustryEvent::outlookAdjustment)
              .orElse(0.0);
      // Small noise around an event-driven reference valuation, with gradual mean reversion.
      double reference =
          referencePrice * (1 + adjustment + commerce.advance(tick) * influenceScale);
      double hours = (tick - observations.getLast().tick) / (double) HOUR;
      var active = activeEvent();
      double halfLife =
          active
              .map(IndustryEvent::responseHalfLifeHours)
              .orElse(eventSettings.value("response-half-life-market-hours"));
      double cap =
          active
              .map(IndustryEvent::maxMovePercent)
              .orElse(eventSettings.value("max-move-percent-per-market-hour"));
      double response = 1 - Math.pow(.5, hours / halfLife);
      double move =
          (reference - price) * response
              + (randomUnit() - .5) * .35 * volatility * Math.sqrt(hours);
      double limit = price * cap / 100 * hours;
      move = Math.max(-limit, Math.min(limit, move));
      price = Math.max(1, Math.min(1000000, price + move));
      observations.add(new PriceObservation(tick, price));
      nextObservation = nextSampleAfter(tick);
    }
    tick = targetTick;
    long cutoff = tick - retentionTicks;
    int remove = 0;
    while (remove < observations.size() - 1 && observations.get(remove).tick < cutoff) remove++;
    if (remove > 0) observations.subList(0, remove).clear();
  }

  private void beginEvent() {
    if (!eventsEnabled) {
      nextEvent = Math.addExact(tick, HOUR);
      return;
    }
    var selection =
        eventEngine.next(tick, ticker, industryName, storyCollection, eventSettings, stories);
    events.add(selection.event());
    if (events.size() > 512) events.removeFirst();
    nextEvent = selection.nextTick();
  }

  private double randomUnit() {
    randomState ^= randomState << 13;
    randomState ^= randomState >>> 7;
    randomState ^= randomState << 17;
    return (randomState >>> 11) * 0x1.0p-53;
  }

  /**
   * Records eligible mapped demand at this simulation’s current tick and returns accepted capped
   * units.
   */
  public synchronized int recordCommerce(CommerceObservations.Trade trade) {
    return commerce.record(tick, trade, items);
  }

  /** Returns the active diversity requirements for this ticker. */
  public CommerceSignal.Requirements commerceRequirements() {
    return commerceRequirements;
  }

  /** Maximum effective outlook influence in percentage points, not an immediate price change. */
  public double commerceCapPercent() {
    return CommerceSignal.MAX_ADJUSTMENT * influenceScale * 100;
  }

  /**
   * Returns trailing evidence and the scaled outlook adjustment; does not advance simulation time.
   */
  public synchronized CommerceSignal.Summary commerceSummary() {
    var s = commerce.summary(tick);
    return new CommerceSignal.Summary(
        s.buyers(),
        s.sellers(),
        s.participants(),
        s.pairs(),
        s.units(),
        s.weightedUnits(),
        s.learning(),
        s.qualified(),
        s.adjustment() * influenceScale);
  }

  /** Returns the latest recorded simulation mark, not a guaranteed settlement price. */
  public synchronized double currentPrice() {
    return price;
  }

  /** Returns simulation time, which may fall between recorded price samples. */
  public synchronized long currentTick() {
    return tick;
  }

  /** Returns the earliest retained observation tick; older prices are unavailable. */
  public synchronized long firstObservationTick() {
    return observations.getFirst().tick;
  }

  /** Returns an immutable chronological snapshot of retained price observations. */
  public synchronized List<PriceObservation> observations() {
    return List.copyOf(observations);
  }

  /** Whether current configuration permits new events, their effects and news delivery. */
  public boolean eventsEnabled() {
    return eventsEnabled;
  }

  /**
   * Returns chronological history, including historical events disabled by current configuration.
   */
  public synchronized List<IndustryEvent> events() {
    return List.copyOf(events);
  }

  /**
   * Returns the current unexpired event, or empty when events are disabled or no event is active.
   */
  public synchronized Optional<IndustryEvent> activeEvent() {
    if (!eventsEnabled) return Optional.empty();
    if (events.isEmpty()) return Optional.empty();
    IndustryEvent last = events.getLast();
    return tick < last.endTick ? Optional.of(last) : Optional.empty();
  }

  /**
   * Empty bins mean no recorded observation. No interpolation or synthetic prehistory is produced.
   */
  public synchronized List<HistoryBin> bars(long nowTicks, TimeRange range, int count) {
    return bars(
        nowTicks, range, count, true); // Rolling metrics remain appropriate for daily reports.
  }

  /**
   * Aggregates actual observations into a rolling or subdivided aligned window. For the UI’s
   * full-period historical columns, pass a ChartWindow.history layout instead.
   */
  public synchronized List<HistoryBin> bars(
      long nowTicks, TimeRange range, int count, boolean live) {
    if (nowTicks < 0 || nowTicks > tick || range == null || count < 1 || count > 128)
      throw new IllegalArgumentException("Invalid history query");
    return bars(ChartWindow.create(nowTicks, range.ticks(), count, live));
  }

  /**
   * Aggregates retained observations into the supplied layout, preserving empty columns. Rejects a
   * layout whose now exceeds simulation time; never infers missing prices.
   */
  public synchronized List<HistoryBin> bars(ChartWindow layout) {
    long nowTicks = layout.now();
    int count = layout.intervals().size();
    boolean live = layout.live();
    if (nowTicks < 0 || nowTicks > tick)
      throw new IllegalArgumentException("Invalid history query");
    List<HistoryBin> result = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      long from = layout.intervals().get(i).start();
      long to = layout.intervals().get(i).end();
      PriceObservation first = null, last = null;
      double high = Double.NEGATIVE_INFINITY, low = Double.POSITIVE_INFINITY;
      int samples = 0;
      for (PriceObservation point : observations) {
        if (point.tick > nowTicks) break;
        if (point.tick < from) continue;
        if (point.tick > to || point.tick == to && !(live && i == count - 1)) break;
        if (first == null) first = point;
        last = point;
        high = Math.max(high, point.price);
        low = Math.min(low, point.price);
        samples++;
      }
      Optional<PriceBar> values =
          first == null
              ? Optional.empty()
              : Optional.of(
                  new PriceBar(first.tick, last.tick, first.price, high, low, last.price));
      result.add(new HistoryBin(from, to, values, samples));
    }
    return List.copyOf(result);
  }

  /** Encodes a simulation checkpoint including observations, PRNG state and commerce evidence. */
  public synchronized byte[] snapshot() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeInt(MAGIC);
      out.writeLong(tick);
      out.writeLong(nextObservation);
      out.writeLong(nextEvent);
      out.writeLong(randomState);
      out.writeDouble(price);
      out.writeInt(observations.size());
      for (PriceObservation point : observations) {
        out.writeLong(point.tick);
        out.writeDouble(point.price);
      }
      out.writeInt(events.size());
      for (IndustryEvent event : events) {
        out.writeLong(event.startTick);
        out.writeLong(event.endTick);
        out.writeUTF(event.title);
        out.writeUTF(event.description);
        out.writeDouble(event.outlookAdjustment);
        out.writeUTF(event.category);
        out.writeUTF(event.collection);
        out.writeUTF(event.storyId);
        out.writeDouble(event.responseHalfLifeHours);
        out.writeDouble(event.maxMovePercent);
      }
      eventEngine.write(out);
      commerce.write(out);
      out.flush();
      CRC32 crc = new CRC32();
      crc.update(bytes.toByteArray());
      out.writeLong(crc.getValue());
    }
    return bytes.toByteArray();
  }

  /** Commits one market checkpoint; runtime uses a coordinated transaction for all markets. */
  public synchronized void save() throws IOException {
    com.thaalur.quadstock.persistence.StateStore.write(directory, checkpoint, snapshot());
  }
}
