package com.thaalur.quadstock.commerce;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional API boundary: resolved once against QuickShop's public interfaces, never shaded. */
public final class QuickShopObserver implements Listener {
  private static final String NAME = "QuickShop-Hikari";
  private final JavaPlugin plugin;
  private final CommerceObservations observations;
  private final java.util.function.Consumer<CommerceObservations.Trade> consumer;
  private final Listener purchases = new Listener() {};
  private final ArrayBlockingQueue<CommerceObservations.Trade> queue =
      new ArrayBlockingQueue<>(1024);
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong errors = new AtomicLong();
  private volatile boolean connected;
  private String state = "QuickShop unavailable";

  /**
   * Creates a detached observer. The consumer is invoked during main-thread drain, never directly
   * from the shop callback. Only scalar snapshots cross the bounded queue.
   */
  public QuickShopObserver(
      JavaPlugin plugin,
      CommerceObservations observations,
      java.util.function.Consumer<CommerceObservations.Trade> consumer) {
    this.plugin = plugin;
    this.observations = observations;
    this.consumer = consumer;
  }

  /**
   * Registers plugin lifecycle listeners and attempts to bind the currently enabled QuickShop API.
   * Call once.
   */
  public void start() {
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    bind(plugin.getServer().getPluginManager().getPlugin(NAME));
  }

  /** Attempts late binding when QuickShop enables; incompatible APIs leave observation inactive. */
  @EventHandler
  public void enabled(PluginEnableEvent event) {
    bind(event.getPlugin());
  }

  /** Detaches purchase listeners when QuickShop disables; no further events are accepted. */
  @EventHandler
  public void disabled(PluginDisableEvent event) {
    if (NAME.equals(event.getPlugin().getName())) {
      connected = false;
      HandlerList.unregisterAll(purchases);
      state = "QuickShop disabled";
    }
  }

  private void bind(Plugin quickShop) {
    if (connected
        || quickShop == null
        || !NAME.equals(quickShop.getName())
        || !quickShop.isEnabled()) return;
    try {
      ClassLoader loader = quickShop.getClass().getClassLoader();
      Class<? extends Event> event =
          loader
              .loadClass("com.ghostchu.quickshop.api.event.economy.ShopSuccessPurchaseEvent")
              .asSubclass(Event.class);
      Class<?> meta = loader.loadClass("com.ghostchu.quickshop.api.shop.meta.ShopMeta");
      Class<?> user = loader.loadClass("com.ghostchu.quickshop.api.obj.QUser");
      Method shop = event.getMethod("getShop"), purchaser = event.getMethod("getPurchaser");
      Method amount = event.getMethod("getAmount"),
          gross = event.getMethod("getBalanceWithoutTax"),
          net = event.getMethod("getBalance");
      Method id = meta.getMethod("getShopId"),
          item = meta.getMethod("getItem"),
          owner = meta.getMethod("getOwner");
      Method unlimited = meta.getMethod("isUnlimited"),
          currency = meta.getMethod("getCurrency"),
          type = meta.getMethod("shopType");
      Method identifier =
          loader.loadClass("com.ghostchu.quickshop.api.shop.IShopType").getMethod("identifier");
      Method realId = user.getMethod("getUniqueIdIfRealPlayer");
      plugin
          .getServer()
          .getPluginManager()
          .registerEvent(
              event,
              purchases,
              EventPriority.MONITOR,
              (listener, raw) -> {
                // QuickShop's base event shares its HandlerList across event subclasses.
                if (!connected || !event.isInstance(raw)) return;
                try {
                  Object source = shop.invoke(raw);
                  // Copy scalar values only; no mutable shop/event objects cross threads.
                  var trade =
                      new CommerceObservations.Trade(
                          (long) id.invoke(source),
                          ((ItemStack) item.invoke(source)).getType().name(),
                          (int) amount.invoke(raw),
                          (String) identifier.invoke(type.invoke(source)),
                          realPlayerId(realId.invoke(owner.invoke(source))),
                          realPlayerId(realId.invoke(purchaser.invoke(raw))),
                          (boolean) unlimited.invoke(source),
                          (String) currency.invoke(source),
                          (double) gross.invoke(raw),
                          (double) net.invoke(raw));
                  if (!queue.offer(trade)) dropped.incrementAndGet();
                } catch (ReflectiveOperationException | RuntimeException failure) {
                  if (errors.incrementAndGet() == 1)
                    plugin.getLogger().warning("QuickShop observation failed: " + failure);
                }
              },
              plugin);
      connected = true;
      state = "QuickShop connected · bounded commerce";
      plugin.getLogger().info(state);
    } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
      HandlerList.unregisterAll(purchases);
      state = "QuickShop API incompatible · observer inactive";
      plugin.getLogger().warning(state + ": " + failure);
    }
  }

  private static UUID realPlayerId(Object result) {
    return (UUID) ((Optional<?>) result).orElse(null);
  }

  /**
   * Called on the server thread. Timestamp is receipt by the market, not a settlement guarantee.
   */
  public void drain(long marketTick) {
    for (int i = 0; i < 128; i++) {
      var trade = queue.poll();
      if (trade == null) break;
      observations.record(marketTick, trade);
      consumer.accept(trade);
    }
  }

  /** Returns the main-thread connection diagnostic; it does not certify commerce qualification. */
  public String status() {
    return state;
  }

  /** Returns events discarded because the 1,024-entry callback queue was full. */
  public long dropped() {
    return dropped.get();
  }

  /** Returns callback decoding failures observed during this adapter’s lifetime. */
  public long errors() {
    return errors.get();
  }

  /**
   * Detaches lifecycle/purchase listeners and discards queued observations. Call on plugin
   * shutdown.
   */
  public void close() {
    connected = false;
    HandlerList.unregisterAll(purchases);
    HandlerList.unregisterAll(this);
    queue.clear();
  }
}
