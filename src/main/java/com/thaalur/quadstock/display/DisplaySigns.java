package com.thaalur.quadstock.display;

import java.io.IOException;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.persistence.PersistentDataType;

/**
 * Conflict-aware sign projection; original content stays in SQLite, ownership travels with the
 * sign.
 */
public final class DisplaySigns {
  private final NamespacedKey owner, digest, sides;

  public DisplaySigns(NamespacedKey owner, NamespacedKey digest, NamespacedKey sides) {
    this.owner = owner;
    this.digest = digest;
    this.sides = sides;
  }

  private static String serializeSide(Sign sign, Side side) {
    var gson = GsonComponentSerializer.gson();
    var result = new ArrayList<String>();
    for (Component c : sign.getSide(side).lines()) result.add(gson.serialize(c));
    return String.join("\n", result);
  }

  public static void capture(Map<String, String> backup, Side side, Sign sign) {
    backup.put(side.name(), serializeSide(sign, side));
    backup.put(side + "-color", sign.getSide(side).getColor().name());
    backup.put(side + "-glow", String.valueOf(sign.getSide(side).isGlowingText()));
  }

  private void restore(Sign sign, Side side, DisplayDefinition d) throws IOException {
    String original = d.originals().get(side.name());
    if (original == null) throw new IOException("Missing sign backup");
    String[] lines = original.split("\n", -1);
    if (lines.length != 4) throw new IOException("Invalid sign backup");
    for (int i = 0; i < 4; i++)
      sign.getSide(side).line(i, GsonComponentSerializer.gson().deserialize(lines[i]));
    sign.getSide(side).setColor(DyeColor.valueOf(d.originals().get(side + "-color")));
    sign.getSide(side).setGlowingText(Boolean.parseBoolean(d.originals().get(side + "-glow")));
  }

  public Set<String> managed(Sign sign) {
    String s = sign.getPersistentDataContainer().get(sides, PersistentDataType.STRING);
    return s == null || s.isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(s.split(",")));
  }

  private String fingerprint(Sign sign) {
    var result = new StringBuilder();
    for (Side side : Side.values())
      if (managed(sign).contains(side.name()))
        result
            .append(serializeSide(sign, side))
            .append(sign.getSide(side).getColor())
            .append(sign.getSide(side).isGlowingText());
    // Keep the full serialized value; equality must not rely on a weak hash.
    return result.toString();
  }

  public void verifyOwner(Sign sign, DisplayDefinition d) throws IOException {
    var p = sign.getPersistentDataContainer();
    String id = p.get(owner, PersistentDataType.STRING);
    if (id != null && !id.equals(d.id().toString()))
      throw new IOException("Sign owned by another registration");
    if (id != null && !Objects.equals(p.get(digest, PersistentDataType.STRING), fingerprint(sign)))
      throw new IOException("Sign content changed externally; restoration conflict");
  }

  public void render(DisplayDefinition d, Sign sign, List<Component> lines, boolean clear)
      throws IOException {
    verifyOwner(sign, d);
    var p = sign.getPersistentDataContainer();
    var before = managed(sign);
    var after = new LinkedHashSet<String>();
    if (!clear) for (Side side : Side.values()) if (d.side(side.name())) after.add(side.name());
    if (!clear && p.get(owner, PersistentDataType.STRING) == null) {
      for (Side side : Side.values())
        if (after.contains(side.name())
            && (!Objects.equals(d.originals().get(side.name()), serializeSide(sign, side))
                || !Objects.equals(
                    d.originals().get(side + "-color"), sign.getSide(side).getColor().name())
                || !Objects.equals(
                    d.originals().get(side + "-glow"),
                    String.valueOf(sign.getSide(side).isGlowingText()))))
          throw new IOException("Original sign changed before activation; refusing overwrite");
    }
    boolean changed = false;
    for (Side side : Side.values()) {
      if (before.contains(side.name()) && !after.contains(side.name())) {
        restore(sign, side, d);
        changed = true;
      }
      if (after.contains(side.name())) {
        for (int i = 0; i < 4; i++) {
          var c = lines.get(i);
          if (!sign.getSide(side).line(i).equals(c)) {
            sign.getSide(side).line(i, c);
            changed = true;
          }
        }
        if (!sign.getSide(side).isGlowingText()) {
          sign.getSide(side).setGlowingText(true);
          changed = true;
        }
      }
    }
    if (clear) {
      if (p.has(owner)) changed = true;
      p.remove(owner);
      p.remove(sides);
      p.remove(digest);
    } else {
      String sideNames = String.join(",", after);
      if (!Objects.equals(p.get(sides, PersistentDataType.STRING), sideNames)) changed = true;
      p.set(owner, PersistentDataType.STRING, d.id().toString());
      p.set(sides, PersistentDataType.STRING, sideNames);
      p.set(digest, PersistentDataType.STRING, fingerprint(sign));
    }
    if (changed && !sign.update(false, false)) throw new IOException("Sign update failed");
  }

  /** Explicitly discard restoration while leaving every visible line unchanged. */
  public void release(DisplayDefinition d, Sign sign) throws IOException {
    var p = sign.getPersistentDataContainer();
    if (d.id().toString().equals(p.get(owner, PersistentDataType.STRING))) {
      p.remove(owner);
      p.remove(sides);
      p.remove(digest);
      if (!sign.update(false, false)) throw new IOException("Cannot release sign ownership");
    }
  }
}
