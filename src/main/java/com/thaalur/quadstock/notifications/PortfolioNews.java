package com.thaalur.quadstock.notifications;

import com.thaalur.quadstock.data.LivingMarket.IndustryEvent;
import java.util.*;

/**
 * Session-only, bounded pending news. Discovery and delivery are separate so cooldown never
 * consumes news.
 */
public final class PortfolioNews {
  public static final int CAPACITY = 128;

  public record Item(String ticker, IndustryEvent event) {}

  private record Key(String ticker, long tick) {}

  private final long joined;
  private final Map<String, Long> seenThrough = new HashMap<>();
  private final LinkedHashMap<Key, Item> pending = new LinkedHashMap<>();
  private final Map<String, Long> omitted = new LinkedHashMap<>();
  private static final Comparator<Item> ORDER =
      Comparator.comparing((Item item) -> !item.event().special())
          .thenComparingLong(item -> item.event().startTick())
          .thenComparing(Item::ticker);

  public PortfolioNews(long joined) {
    this.joined = joined;
  }

  /**
   * Histories are chronological. No pre-login replay; sold or disabled tickers lose pending
   * relevance.
   */
  public void discover(String ticker, List<IndustryEvent> history, boolean relevant, long now) {
    long through = seenThrough.getOrDefault(ticker, joined);
    if (!relevant) {
      pending.keySet().removeIf(key -> key.ticker.equals(ticker));
      omitted.remove(ticker);
      seenThrough.put(ticker, Math.max(through, now));
      return;
    }
    for (var event : history) {
      if (event.startTick() <= through || event.startTick() > now) continue;
      pending.put(new Key(ticker, event.startTick()), new Item(ticker, event));
      if (pending.size() > CAPACITY) {
        // Keep specials preferentially; evict oldest ordinary news first, explicitly counting
        // omissions.
        Key eviction =
            pending.entrySet().stream()
                .min(
                    Comparator.comparing((Map.Entry<Key, Item> e) -> e.getValue().event.special())
                        .thenComparingLong(e -> e.getKey().tick())
                        .thenComparing(e -> e.getKey().ticker()))
                .map(Map.Entry::getKey)
                .orElseThrow();
        pending.remove(eviction);
        omitted.merge(eviction.ticker(), 1L, Long::sum);
      }
    }
    seenThrough.put(ticker, Math.max(through, now));
  }

  public List<Item> batch(int limit) {
    if (limit < 1 || limit > 18) throw new IllegalArgumentException("News limit must be 1–18");
    return pending.values().stream().sorted(ORDER).limit(limit).toList();
  }

  public int pendingCount() {
    return pending.size();
  }

  public long omittedCount() {
    return omitted.values().stream().mapToLong(Long::longValue).sum();
  }

  public boolean hasNews() {
    return !pending.isEmpty() || !omitted.isEmpty();
  }

  /** Acknowledge only after the digest has been handed to the player successfully. */
  public void delivered(List<Item> items, long reportedOmissions) {
    for (var item : items) pending.remove(new Key(item.ticker(), item.event().startTick()));
    long remaining = reportedOmissions;
    var iterator = omitted.entrySet().iterator();
    while (iterator.hasNext() && remaining > 0) {
      var entry = iterator.next();
      long consumed = Math.min(remaining, entry.getValue());
      remaining -= consumed;
      if (consumed == entry.getValue()) iterator.remove();
      else entry.setValue(entry.getValue() - consumed);
    }
  }
}
