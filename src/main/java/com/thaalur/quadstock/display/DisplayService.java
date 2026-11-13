package com.thaalur.quadstock.display;

import com.thaalur.quadstock.config.MarketSettings;
import com.thaalur.quadstock.data.LivingMarket;
import com.thaalur.quadstock.security.Permissions;
import com.thaalur.quadstock.trading.HoldingsLedger;
import com.thaalur.quadstock.ui.MarketMenus;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.*;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.HangingSign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Server-thread owner of persisted registrations and disposable world projections. */
public final class DisplayService implements AutoCloseable {
  public static final String ADMIN = "quadstock.display.admin";
  private final JavaPlugin plugin;
  private final DisplaySettings policy;
  private final MarketSettings settings;
  private final Map<String, LivingMarket> markets;
  private final Map<String, MarketMenus> screens;
  private final Supplier<String> commerceStatus;
  private final DisplayStore store;
  private final NamespacedKey owner, digest, sides;
  private final DisplaySigns signWriter;
  private final Map<UUID, FloatingText> texts = new HashMap<>();
  private final Map<UUID, Interaction> hits = new HashMap<>();
  private final Map<UUID, String> statuses = new HashMap<>(), headlines = new HashMap<>();
  private final Map<UUID, Long> headlineStart = new HashMap<>(), clickTimes = new HashMap<>();
  private final Map<String, DisplayRenderer.Snapshot> snapshots = new HashMap<>();

  private record Frame(
      long revision, DisplayRenderer.Snapshot snapshot, int width, List<Component> lines) {}

  private final Map<UUID, Frame> frames = new HashMap<>();
  private long snapshotAt;
  private final Map<String, DisplayDefinition> anchors = new HashMap<>();
  private final Set<String> protectedPositions = new HashSet<>();
  private boolean failed;
  private DisplayEditor editor;

  public DisplayService(
      JavaPlugin plugin,
      DisplaySettings policy,
      MarketSettings settings,
      Map<String, LivingMarket> markets,
      Map<String, MarketMenus> screens,
      Supplier<String> commerceStatus)
      throws IOException {
    this.plugin = plugin;
    this.policy = policy;
    this.settings = settings;
    this.markets = markets;
    this.screens = screens;
    this.commerceStatus = commerceStatus;
    owner = new NamespacedKey(plugin, "display-owner");
    digest = new NamespacedKey(plugin, "display-digest");
    sides = new NamespacedKey(plugin, "display-sides");
    signWriter = new DisplaySigns(owner, digest, sides);
    store = new DisplayStore(plugin.getDataFolder().toPath());
    rebuild();
  }

  public void start() {
    editor = new DisplayEditor(this, plugin);
    plugin.getServer().getPluginManager().registerEvents(editor, plugin);
    plugin.getServer().getPluginManager().registerEvents(new DisplayProtection(this), plugin);
    for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) cleanStale(c);
    plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 2);
  }

  public DisplayEditor editor() {
    return editor;
  }

  public DisplaySettings policy() {
    return policy;
  }

  public Collection<String> tickers() {
    return settings.industries().keySet();
  }

  public Collection<DisplayDefinition> all() {
    return List.copyOf(store.all().values());
  }

  public DisplayDefinition get(UUID id) {
    return store.all().get(id);
  }

  public String status(UUID id) {
    return failed
        ? "Storage failure; restart required"
        : statuses.getOrDefault(id, "Awaiting reconciliation");
  }

  public static String key(Block b) {
    return b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
  }

  public DisplayDefinition at(Block b) {
    return anchors.get(key(b));
  }

  public boolean protectedBlock(Block b) {
    return protectedPositions.contains(key(b));
  }

  public boolean owned(Entity e) {
    return e.getPersistentDataContainer().has(owner, PersistentDataType.STRING);
  }

  public void cleanStale(Chunk chunk) {
    for (Entity e : chunk.getEntities())
      if (owned(e)
          && texts.values().stream().noneMatch(text -> text.owns(e))
          && !hits.containsValue(e)) e.remove();
  }

  public void unload(Chunk c) {
    for (var d : all())
      if (d.anchor().world().equals(c.getWorld().getUID())
          && d.anchor().x() >> 4 == c.getX()
          && d.anchor().z() >> 4 == c.getZ()) removeEntities(d.id());
  }

  public DisplayDefinition target(Player p) {
    Block b = p.getTargetBlockExact(6);
    if (b == null) throw new IllegalArgumentException("Look at a display block within six blocks");
    var d = at(b);
    if (d == null) throw new IllegalArgumentException("That block has no display");
    return d;
  }

  public DisplayDefinition draft(Player p, String ticker, DisplayDefinition.Surface surface) {
    Block b = p.getTargetBlockExact(6);
    if (b == null) throw new IllegalArgumentException("Look at an allowed block within six blocks");
    if (at(b) != null) throw new IllegalArgumentException("This block already owns a display");
    validateAnchor(b, surface);
    float yaw = p.getYaw() + 180;
    var data = b.getBlockData();
    org.bukkit.block.BlockFace facing =
        data instanceof org.bukkit.block.data.Directional directional
            ? directional.getFacing()
            : data instanceof org.bukkit.block.data.Rotatable rotated
                ? rotated.getRotation()
                : null;
    if (facing != null && facing.getModY() == 0 && (facing.getModX() != 0 || facing.getModZ() != 0))
      yaw = (float) Math.toDegrees(Math.atan2(-facing.getModX(), facing.getModZ()));
    while (yaw > 180) yaw -= 360;
    while (yaw < -180) yaw += 360;
    return DisplayDefinition.create(
        new DisplayDefinition.Anchor(
            b.getWorld().getUID(),
            b.getX(),
            b.getY(),
            b.getZ(),
            b.getType().name(),
            b.getBlockData().getAsString()),
        ticker,
        surface,
        yaw);
  }

  private void validateAnchor(Block b, DisplayDefinition.Surface surface) {
    if (!policy.allowed().contains(b.getType().name()))
      throw new IllegalArgumentException(
          "Anchor material not allowed: "
              + b.getType()
              + ". Edit displays.allowed-anchor-materials");
    if (!b.getType().isBlock()
        || b.isEmpty()
        || b.isLiquid()
        || b.getType().hasGravity()
        || b.getBlockData() instanceof Bisected
        || b.getBlockData() instanceof Bed
        || b.getType() == Material.TNT
        || b.getBlockData() instanceof Piston
        || b.getBlockData() instanceof TechnicalPiston
        || b.getType().name().contains("PORTAL")
        || b.getType().name().contains("FIRE")
        || (!(b.getState() instanceof Sign) && !b.getType().isSolid()))
      throw new IllegalArgumentException(
          "Unsupported anchor state: " + b.getType() + " (must be stable and protectable)");
    if (surface == DisplayDefinition.Surface.SIGN && !(b.getState() instanceof Sign))
      throw new IllegalArgumentException("Sign appearance requires a sign");
  }

  private Block loaded(DisplayDefinition d) {
    var a = d.anchor();
    World w = Bukkit.getWorld(a.world());
    return w == null || !w.isChunkLoaded(a.x() >> 4, a.z() >> 4)
        ? null
        : w.getBlockAt(a.x(), a.y(), a.z());
  }

  private boolean matches(DisplayDefinition d, Block b) {
    return b.getType().name().equals(d.anchor().material())
        && b.getBlockData().getAsString().equals(d.anchor().blockData());
  }

  public void save(DisplayDefinition draft) throws IOException {
    if (failed) throw new IOException("Display storage failed; restart before editing");
    var old = get(draft.id());
    if (old != null && old.removed()) throw new IOException("Removal is pending");
    if (old == null && all().size() >= policy.maximum())
      throw new IOException("Display limit reached");
    if (!markets.containsKey(draft.ticker())) throw new IOException("Unknown ticker");
    var b = loaded(draft);
    if (b == null || !matches(draft, b))
      throw new IOException("Anchor unavailable or changed; target it again");
    validateAnchor(b, draft.surface());
    var backup = new HashMap<>(draft.originals());
    var supportLocations = new LinkedHashSet<String>();
    support(b, new HashSet<>(), 0, supportLocations);
    backup.put("supports", String.join("\n", supportLocations));
    if (b.getState() instanceof Sign sign) {
      signWriter.verifyOwner(sign, draft);
      if (draft.surface() == DisplayDefinition.Surface.SIGN)
        for (Side side : Side.values())
          if (draft.side(side.name()) && !signWriter.managed(sign).contains(side.name()))
            DisplaySigns.capture(backup, side, sign);
    }
    persist(draft.saved(backup, false), draft.revision());
    snapshots.clear();
    removeEntities(draft.id());
    rebuild();
    tick();
  }

  public void remove(DisplayDefinition d) throws IOException {
    remove(d, false);
  }

  /** Explicit keep-content removal releases ownership without restoring conflicting sign text. */
  public void remove(DisplayDefinition d, boolean keepContent) throws IOException {
    if (failed) throw new IOException("Display storage failed; restart before editing");
    var backup = new HashMap<>(d.originals());
    if (keepContent) backup.put("keep-content", "true");
    persist(d.saved(backup, true), d.revision());
    removeEntities(d.id());
    rebuild();
    tick();
  }

  private void persist(DisplayDefinition d, long revision) throws IOException {
    try {
      store.put(d, revision);
    } catch (DisplayStore.StorageException e) {
      fail(e);
      throw e;
    }
  }

  private void fail(IOException e) {
    failed = true;
    plugin
        .getLogger()
        .log(
            java.util.logging.Level.SEVERE,
            "Display storage unavailable; suspending displays until restart",
            e);
    close();
  }

  private void rebuild() {
    anchors.clear();
    protectedPositions.clear();
    for (var d : all()) {
      anchors.put(d.anchor().locationKey(), d);
      protectedPositions.addAll(d.protectedLocations());
    }
  }

  private void support(Block b, Set<String> visited, int depth, Set<String> locations)
      throws IOException {
    if (depth > 32) throw new IOException("Sign support chain exceeds 32 blocks");
    if (!visited.add(key(b))) return;
    var data = b.getBlockData();
    var supports = new ArrayList<Block>();
    if (data instanceof WallSign w) supports.add(b.getRelative(w.getFacing().getOppositeFace()));
    else if (data instanceof WallHangingSign w) {
      var facing = w.getFacing();
      var axis = facing == BlockFace.NORTH || facing == BlockFace.SOUTH;
      supports.add(b.getRelative(axis ? BlockFace.EAST : BlockFace.NORTH));
      supports.add(b.getRelative(axis ? BlockFace.WEST : BlockFace.SOUTH));
    } else if (data instanceof HangingSign) supports.add(b.getRelative(BlockFace.UP));
    else if (b.getState() instanceof Sign) supports.add(b.getRelative(BlockFace.DOWN));
    for (var s : supports) {
      if (!s.getWorld().isChunkLoaded(s.getX() >> 4, s.getZ() >> 4))
        throw new IOException("Sign support chunk must be loaded while saving");
      if (s.isEmpty()) continue;
      locations.add(key(s));
      if (s.getState() instanceof Sign) support(s, visited, depth + 1, locations);
    }
  }

  private DisplayRenderer.Snapshot snapshot(DisplayDefinition d) {
    return snapshots.computeIfAbsent(
        d.ticker() + "/" + d.mode(),
        k ->
            DisplayRenderer.snapshot(
                settings.industries().get(d.ticker()).name(),
                markets.get(d.ticker()),
                d.mode(),
                commerceStatus.get()));
  }

  private long age(DisplayDefinition d, DisplayRenderer.Snapshot s, long now) {
    String headline = s.news() == null ? "" : s.news().startTick() + "/" + s.news().title();
    if (!Objects.equals(headlines.put(d.id(), headline), headline)) headlineStart.put(d.id(), now);
    return now - headlineStart.getOrDefault(d.id(), now);
  }

  private boolean nearby(Block b) {
    for (Player p : b.getWorld().getPlayers())
      if (p.getLocation().distanceSquared(b.getLocation().add(.5, .5, .5))
          <= policy.viewDistance() * policy.viewDistance()) return true;
    return false;
  }

  private void tick() {
    if (failed) return;
    long now = System.nanoTime() / 1_000_000;
    if (now - snapshotAt >= settings.refreshSeconds() * 1000) {
      snapshots.clear();
      snapshotAt = now;
    }
    for (var d : all()) {
      var b = loaded(d);
      if (b == null) {
        statuses.put(d.id(), "Dormant: world/chunk unloaded");
        removeEntities(d.id());
        continue;
      }
      try {
        if (d.removed()) {
          if (matches(d, b) && b.getState() instanceof Sign s) {
            if (Boolean.parseBoolean(d.originals().get("keep-content"))) {
              signWriter.release(d, s);
            } else signWriter.render(d, s, List.of(), true);
          }
          removeEntities(d.id());
          store.forget(d.id());
          statuses.remove(d.id());
          rebuild();
          continue;
        }
        if (!matches(d, b)) throw new IOException("Suspended: anchor changed/missing");
        boolean available =
            policy.allowed().contains(d.anchor().material()) && markets.containsKey(d.ticker());
        if (!available) {
          removeEntities(d.id());
          if (d.surface() == DisplayDefinition.Surface.SIGN && b.getState() instanceof Sign s)
            signWriter.render(d, s, offline("Suspended"), false);
          statuses.put(d.id(), "Suspended: anchor policy or ticker unavailable");
          continue;
        }
        if (d.surface() == DisplayDefinition.Surface.FLOATING
            && b.getState() instanceof Sign s
            && s.getPersistentDataContainer().has(owner)) signWriter.render(d, s, List.of(), true);
        if (!nearby(b)) {
          removeEntities(d.id());
          statuses.put(d.id(), "Active: no nearby viewers");
          continue;
        }
        var snap = snapshot(d);
        int width =
            d.surface() == DisplayDefinition.Surface.SIGN
                ? (b.getType().name().contains("HANGING") ? 60 : 90)
                : 240;
        var frame = frames.get(d.id());
        if (frame == null
            || frame.revision() != d.revision()
            || frame.snapshot() != snap
            || frame.width() != width
            || d.type() == DisplayDefinition.Type.NEWS) {
          frame =
              new Frame(
                  d.revision(),
                  snap,
                  width,
                  DisplayRenderer.render(d, snap, width, age(d, snap, now)));
          frames.put(d.id(), frame);
        }
        var lines = frame.lines();
        if (d.surface() == DisplayDefinition.Surface.SIGN) {
          if (!(b.getState() instanceof Sign s)) throw new IOException("Sign missing");
          signWriter.render(d, s, lines, false);
        } else floating(d, b, lines);
        statuses.put(d.id(), "Active");
      } catch (DisplayStore.StorageException e) {
        fail(e);
        return;
      } catch (IOException | IllegalArgumentException e) {
        removeEntities(d.id());
        String message = e.getMessage();
        if (!Objects.equals(statuses.put(d.id(), message), message))
          plugin.getLogger().warning("Display " + d.id() + ": " + message);
      }
    }
  }

  private List<Component> offline(String reason) {
    return List.of(
        Component.text("QuadStock", NamedTextColor.GRAY),
        Component.text(reason, NamedTextColor.GRAY),
        Component.empty(),
        Component.empty());
  }

  private Location position(DisplayDefinition d, Block b) {
    return b.getLocation().add(.5 + d.number("x"), d.number("height"), .5 + d.number("z"));
  }

  private FloatingText spawnText(
      DisplayDefinition d, Block b, List<Component> lines, Player preview) {
    var planes = DisplayMount.faces(d.number("yaw"));
    for (var plane : planes) {
      var at = position(d, b).add(plane.x(), 0, plane.z());
      if (!b.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4))
        throw new IllegalArgumentException("Text position chunk is unloaded");
    }
    var front = spawnFace(d, b, lines, preview, planes.getFirst());
    try {
      return new FloatingText(front, spawnFace(d, b, lines, preview, planes.getLast()));
    } catch (RuntimeException e) {
      front.remove();
      throw e;
    }
  }

  private TextDisplay spawnFace(
      DisplayDefinition d, Block b, List<Component> lines, Player preview, DisplayMount.Face face) {
    var at = position(d, b).add(face.x(), 0, face.z());
    if (!b.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4))
      throw new IllegalArgumentException("Text position chunk is unloaded");
    var text =
        b.getWorld()
            .spawn(
                at,
                TextDisplay.class,
                e -> {
                  e.setPersistent(false);
                  e.setBillboard(Display.Billboard.FIXED);
                  e.setRotation(face.yaw(), 0);
                  var transform = e.getTransformation();
                  transform.getScale().set((float) d.number("size"));
                  e.setTransformation(transform);
                  e.setLineWidth(260);
                  e.setShadowed(true);
                  e.setSeeThrough(false);
                  e.setBackgroundColor(Color.fromARGB(180, 12, 16, 22));
                  e.setViewRange(policy.viewDistance() / 64f);
                  e.getPersistentDataContainer()
                      .set(owner, PersistentDataType.STRING, d.id().toString());
                  if (preview != null) e.setVisibleByDefault(false);
                  e.text(
                      Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines));
                });
    if (preview != null) preview.showEntity(plugin, text);
    return text;
  }

  private void floating(DisplayDefinition d, Block b, List<Component> lines) {
    var text = texts.get(d.id());
    if (text == null || !text.isValid()) {
      if (text != null) text.remove();
      text = spawnText(d, b, lines, null);
      texts.put(d.id(), text);
    }
    var content = Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines);
    text.text(content);
    var hit = hits.get(d.id());
    if (hit == null || !hit.isValid()) {
      hit =
          b.getWorld()
              .spawn(
                  position(d, b).subtract(0, .2, 0),
                  Interaction.class,
                  e -> {
                    e.setPersistent(false);
                    e.setInteractionWidth((float) Math.min(.9, d.number("size") * .8));
                    e.setInteractionHeight((float) Math.min(2, d.number("size")));
                    e.setResponsive(true);
                    e.getPersistentDataContainer()
                        .set(owner, PersistentDataType.STRING, d.id().toString());
                  });
      hits.put(d.id(), hit);
      for (Player player : b.getWorld().getPlayers())
        DisplayHitTargets.visibility(plugin, player, List.of(hit), player.isSneaking());
    }
  }

  /** Leave floating text visible while allowing crouching players to target the anchor. */
  public void hitVisibility(Player player, boolean sneaking) {
    DisplayHitTargets.visibility(plugin, player, hits.values(), sneaking);
  }

  public FloatingText preview(Player p, DisplayDefinition d) {
    var b = loaded(d);
    if (b == null || !markets.containsKey(d.ticker()))
      throw new IllegalArgumentException("Preview anchor/ticker unavailable");
    var lines =
        DisplayRenderer.render(
            d, snapshot(d), d.surface() == DisplayDefinition.Surface.SIGN ? 60 : 240, 0);
    return spawnText(d, b, lines, p);
  }

  /** Refreshes a private preview without claiming or modifying its anchor. */
  public void refreshPreview(DisplayDefinition d, FloatingText text, long elapsedMillis) {
    var lines =
        DisplayRenderer.render(
            d,
            snapshot(d),
            d.surface() == DisplayDefinition.Surface.SIGN ? 60 : 240,
            elapsedMillis);
    var content = Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines);
    text.text(content);
  }

  private void removeEntities(UUID id) {
    frames.remove(id);
    var t = texts.remove(id);
    if (t != null) t.remove();
    var h = hits.remove(id);
    if (h != null) h.remove();
  }

  public void click(Player p, DisplayDefinition d) {
    if (d.removed() || !status(d.id()).startsWith("Active")) {
      p.sendMessage("Display unavailable: " + status(d.id()));
      return;
    }
    long now = System.nanoTime();
    if (now - clickTimes.getOrDefault(p.getUniqueId(), 0L) < 300_000_000L) return;
    clickTimes.put(p.getUniqueId(), now);
    if (!Permissions.require(p, Permissions.USE)) return;
    var menu = screens.get(d.ticker());
    if (menu == null) return;
    switch (d.type()) {
      case SUMMARY, CHART ->
          menu.openDisplayTicker(p, d.mode().range(), d.mode() == DisplayDefinition.Mode.LIVE);
      case PRICE -> menu.openTrade(p, HoldingsLedger.Side.BUY, 1);
      case NEWS, ACTIVITY -> menu.openReports(p);
    }
  }

  public void click(Player p, Entity entity) {
    String id = entity.getPersistentDataContainer().get(owner, PersistentDataType.STRING);
    if (id != null)
      try {
        var d = get(UUID.fromString(id));
        if (d != null) click(p, d);
      } catch (IllegalArgumentException ignored) {
      }
  }

  public void forgetPlayer(UUID id) {
    clickTimes.remove(id);
  }

  public void chunkLoaded(Chunk c) {
    cleanStale(c);
    rebuild();
  }

  @Override
  public void close() {
    if (editor != null) editor.close();
    for (var d : all()) {
      removeEntities(d.id());
      var b = loaded(d);
      if (b != null
          && matches(d, b)
          && d.surface() == DisplayDefinition.Surface.SIGN
          && !d.removed()
          && b.getState() instanceof Sign s)
        try {
          signWriter.render(d, s, offline("Offline"), false);
        } catch (IOException e) {
          plugin.getLogger().warning("Display shutdown: " + e.getMessage());
        }
    }
  }
}
