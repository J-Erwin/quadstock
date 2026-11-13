package com.thaalur.quadstock.display;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Draft-only admin inventory. Preview is private and Save is the sole publication action. */
public final class DisplayEditor implements Listener, AutoCloseable {
  private final DisplayService service;
  private final JavaPlugin plugin;
  private final Map<UUID, Session> sessions = new HashMap<>();
  private static final List<String> FIELDS =
      List.of(
          "ticker",
          "type",
          "surface",
          "period",
          "sides",
          "yaw",
          "height",
          "x",
          "z",
          "size",
          "accent",
          "scroll-ms",
          "pause-ms");

  private static final class Session implements InventoryHolder {
    DisplayDefinition draft;
    Inventory inventory;
    FloatingText preview;
    long touched = System.nanoTime();
    long previewStarted;

    Session(DisplayDefinition d) {
      draft = d;
    }

    @Override
    public Inventory getInventory() {
      return inventory;
    }
  }

  public DisplayEditor(DisplayService service, JavaPlugin plugin) {
    this.service = service;
    this.plugin = plugin;
    plugin
        .getServer()
        .getScheduler()
        .runTaskTimer(
            plugin,
            () -> {
              for (var e : List.copyOf(sessions.entrySet()))
                if (System.nanoTime() - e.getValue().touched > 300_000_000_000L) {
                  var p = Bukkit.getPlayer(e.getKey());
                  if (p != null) p.closeInventory();
                  discard(e.getKey());
                }
            },
            200,
            200);
    plugin
        .getServer()
        .getScheduler()
        .runTaskTimer(
            plugin,
            () -> {
              for (var s : sessions.values()) {
                if (s.preview == null) continue;
                if (!s.preview.isValid()) {
                  s.preview.remove();
                  s.preview = null;
                  continue;
                }
                service.refreshPreview(
                    s.draft, s.preview, (System.nanoTime() - s.previewStarted) / 1_000_000);
              }
            },
            2,
            2);
  }

  public void open(Player p, DisplayDefinition draft) {
    if (!p.hasPermission(DisplayService.ADMIN)) return;
    discard(p.getUniqueId());
    var s = new Session(draft);
    s.inventory = Bukkit.createInventory(s, 36, Component.text("Market display · Draft"));
    sessions.put(p.getUniqueId(), s);
    paint(s);
    p.openInventory(s.inventory);
  }

  private void paint(Session s) {
    s.inventory.clear();
    for (int i = 0; i < FIELDS.size(); i++) {
      String key = FIELDS.get(i);
      if (key.equals("sides") && s.draft.surface() != DisplayDefinition.Surface.SIGN) continue;
      if (List.of("yaw", "height", "x", "z", "size").contains(key)
          && s.draft.surface() != DisplayDefinition.Surface.FLOATING) continue;
      if (List.of("scroll-ms", "pause-ms").contains(key)
          && s.draft.type() != DisplayDefinition.Type.NEWS) continue;
      if (key.equals("period")
          && (s.draft.type() == DisplayDefinition.Type.NEWS
              || s.draft.type() == DisplayDefinition.Type.ACTIVITY)) continue;
      s.inventory.setItem(
          i,
          item(
              Material.PAPER,
              key + ": " + s.draft.options().get(key),
              "Left: next/increase · Right: previous/decrease"));
    }
    s.inventory.setItem(
        27,
        item(Material.ENDER_EYE, "Preview", "Private text preview; sign text remains unchanged"));
    s.inventory.setItem(
        29,
        item(
            Material.EMERALD_BLOCK,
            "Save",
            "Registered block right-click opens its market screen",
            "This replaces the block's normal right-click action"));
    s.inventory.setItem(31, item(Material.BARRIER, "Cancel", "Discard unsaved changes"));
    s.inventory.setItem(
        33,
        item(
            Material.REDSTONE_BLOCK,
            "Remove display",
            "Shift-click to remove registration and restore sign"));
    s.inventory.setItem(
        35,
        item(
            Material.BOOK,
            "Allowed anchors",
            "Edit displays.allowed-anchor-materials in config.yml",
            "Then /qm reload; use /qm display allowed to list"));
  }

  private static ItemStack item(Material material, String label, String... lore) {
    var item = new ItemStack(material);
    var meta = item.getItemMeta();
    meta.displayName(
        Component.text(label, NamedTextColor.AQUA)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false));
    meta.lore(
        Arrays.stream(lore)
            .map(
                s ->
                    Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            .toList());
    item.setItemMeta(meta);
    return item;
  }

  private String cycle(String current, List<String> values, int direction) {
    return values.get(Math.floorMod(values.indexOf(current) + direction, values.size()));
  }

  private DisplayDefinition adjust(DisplayDefinition d, String key, int direction) {
    String value = d.options().get(key);
    value =
        switch (key) {
          case "ticker" -> cycle(value, new ArrayList<>(service.tickers()), direction);
          case "type" ->
              cycle(
                  value,
                  Arrays.stream(DisplayDefinition.Type.values()).map(Enum::name).toList(),
                  direction);
          case "surface" -> cycle(value, List.of("SIGN", "FLOATING"), direction);
          case "period" ->
              cycle(
                  value,
                  Arrays.stream(DisplayDefinition.Mode.values()).map(Enum::name).toList(),
                  direction);
          case "sides" -> cycle(value, List.of("FRONT", "BACK", "BOTH"), direction);
          case "accent" ->
              cycle(
                  value, List.of("#55FFFF", "#FFFFFF", "#FFAA00", "#FFFF55", "#AA55FF"), direction);
          default ->
              String.valueOf(
                  d.number(key)
                      + direction
                          * switch (key) {
                            case "yaw" -> 90;
                            case "scroll-ms" -> 100;
                            case "pause-ms" -> 500;
                            default -> .25;
                          });
        };
    if (key.equals("yaw")) value = Float.toString(DisplayMount.snap(Double.parseDouble(value)));
    return d.option(key, value);
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Session s)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p)) return;
    if (!p.hasPermission(DisplayService.ADMIN)) {
      p.closeInventory();
      return;
    }
    if (sessions.get(p.getUniqueId()) != s) return;
    s.touched = System.nanoTime();
    int slot = e.getRawSlot();
    try {
      if (slot >= 0 && slot < FIELDS.size() && s.inventory.getItem(slot) != null) {
        s.draft = adjust(s.draft, FIELDS.get(slot), e.isRightClick() ? -1 : 1);
        if (s.preview != null) {
          s.preview.remove();
          s.preview = null;
        }
        paint(s);
      } else if (slot == 27) {
        if (s.preview != null) s.preview.remove();
        s.preview = service.preview(p, s.draft);
        s.previewStarted = System.nanoTime();
      } else if (slot == 29) {
        service.save(s.draft);
        p.sendMessage("Display saved: " + s.draft.id());
        p.closeInventory();
      } else if (slot == 31) p.closeInventory();
      else if (slot == 33 && e.isShiftClick()) {
        var saved = service.get(s.draft.id());
        if (saved == null) throw new IllegalArgumentException("This draft has not been saved");
        if (saved.revision() != s.draft.revision())
          throw new IllegalArgumentException("Display changed; reopen editor");
        service.remove(saved);
        p.sendMessage("Display removal requested");
        p.closeInventory();
      }
    } catch (Exception ex) {
      p.sendMessage("Display: " + ex.getMessage());
    }
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Session) e.setCancelled(true);
  }

  @EventHandler
  public void closeInventory(InventoryCloseEvent e) {
    if (e.getInventory().getHolder() instanceof Session s
        && sessions.get(e.getPlayer().getUniqueId()) == s) discard(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    discard(e.getPlayer().getUniqueId());
  }

  private void discard(UUID id) {
    var s = sessions.remove(id);
    if (s != null && s.preview != null) s.preview.remove();
  }

  @Override
  public void close() {
    for (UUID id : List.copyOf(sessions.keySet())) {
      var p = Bukkit.getPlayer(id);
      if (p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof Session)
        p.closeInventory();
      discard(id);
    }
  }
}
