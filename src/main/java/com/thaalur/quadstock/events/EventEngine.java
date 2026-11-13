package com.thaalur.quadstock.events;

import com.thaalur.quadstock.data.LivingMarket.IndustryEvent;
import com.thaalur.quadstock.data.MarketCalendar;
import java.io.*;
import java.util.*;

/**
 * Persisted event scheduling state. Narrative selection never advances the economic random stream.
 */
public final class EventEngine {
  private long economicRandom, narrativeRandom, specialEligibleAt;
  private final Map<String, String> lastStories = new LinkedHashMap<>();

  public record Selection(IndustryEvent event, long nextTick) {}

  public EventEngine(String ticker) {
    economicRandom = 0x4556454e54534cL ^ ticker.hashCode();
    narrativeRandom = 0x53544f52494553L ^ ticker.hashCode();
  }

  /** Chooses one event, preserving a story's fully resolved text and economic terms. */
  public Selection next(
      long tick,
      String ticker,
      String company,
      String collection,
      EventSettings settings,
      StoryCatalog catalog) {
    boolean special =
        settings.specialEnabled()
            && tick >= specialEligibleAt
            && economicUnit() * 100 < settings.value("special.chance-percent");
    String prefix = special ? "special." : "";
    double positive = settings.value(prefix + "positive-weight"),
        negative = settings.value(prefix + "negative-weight");
    double total = positive + negative + (special ? 0 : settings.value("neutral-weight"));
    double draw = economicUnit() * total;
    String direction =
        draw < positive ? "positive" : draw < positive + negative ? "negative" : "neutral";
    String category = (special ? "special-" : "") + direction;
    double effect =
        direction.equals("neutral")
            ? 0
            : range(settings, prefix + direction + "-impact", "-percent") / 100;
    if (direction.equals("negative")) effect = -effect;
    long end = Math.addExact(tick, ticks(range(settings, prefix + "duration", "-market-hours")));
    long next = Math.addExact(end, ticks(range(settings, "gap", "-market-hours")));
    if (special)
      specialEligibleAt =
          Math.addExact(
              end,
              Math.round(
                  settings.value("special.cooldown-market-days") * MarketCalendar.DAY_TICKS));
    List<StoryCatalog.Story> bank = catalog.stories(collection, category);
    String prior = lastStories.get(category);
    var candidates =
        bank.size() > 1 ? bank.stream().filter(s -> !s.id().equals(prior)).toList() : bank;
    if (candidates.isEmpty())
      throw new IllegalStateException("No stories in " + collection + "." + category);
    StoryCatalog.Story story = candidates.get((int) (narrativeUnit() * candidates.size()));
    lastStories.put(category, story.id());
    return new Selection(
        new IndustryEvent(
            tick,
            end,
            story.headline(company, ticker),
            story.description(company, ticker),
            effect,
            category,
            collection,
            story.id(),
            settings.value(prefix + "response-half-life-market-hours"),
            settings.value(prefix + "max-move-percent-per-market-hour")),
        next);
  }

  private double range(EventSettings s, String key, String unit) {
    double min = s.value(key + "-min" + unit), max = s.value(key + "-max" + unit);
    return min + economicUnit() * (max - min);
  }

  private static long ticks(double hours) {
    return Math.max(1, Math.round(hours * MarketCalendar.HOUR_TICKS));
  }

  private static long advance(long state) {
    state ^= state << 13;
    state ^= state >>> 7;
    return state ^ (state << 17);
  }

  private double economicUnit() {
    economicRandom = advance(economicRandom);
    return (economicRandom >>> 11) * 0x1.0p-53;
  }

  private double narrativeUnit() {
    narrativeRandom = advance(narrativeRandom);
    return (narrativeRandom >>> 11) * 0x1.0p-53;
  }

  public void write(DataOutput out) throws IOException {
    out.writeLong(economicRandom);
    out.writeLong(narrativeRandom);
    out.writeLong(specialEligibleAt);
    out.writeInt(lastStories.size());
    for (var e : lastStories.entrySet()) {
      out.writeUTF(e.getKey());
      out.writeUTF(e.getValue());
    }
  }

  public static EventEngine read(DataInput in) throws IOException {
    var engine = new EventEngine("state");
    engine.economicRandom = in.readLong();
    engine.narrativeRandom = in.readLong();
    engine.specialEligibleAt = in.readLong();
    int count = in.readInt();
    if (engine.economicRandom == 0
        || engine.narrativeRandom == 0
        || engine.specialEligibleAt < 0
        || count < 0
        || count > 5) throw new IOException("Invalid event selection state");
    for (int i = 0; i < count; i++) {
      String category = in.readUTF(), id = in.readUTF();
      if (!StoryCatalog.CATEGORIES.contains(category)
          || !id.matches("[a-z][a-z0-9_-]{0,63}")
          || engine.lastStories.put(category, id) != null)
        throw new IOException("Invalid story selection history");
    }
    return engine;
  }
}
