package com.thaalur.quadstock.display;

import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.*;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.Test;

/** Exercises sign ownership/restore against independent state snapshots, without a server. */
public class DisplaySignsTest {
  private static final NamespacedKey OWNER = new NamespacedKey("quadstock", "display-owner");
  private static final NamespacedKey DIGEST = new NamespacedKey("quadstock", "display-digest");
  private static final NamespacedKey SIDES = new NamespacedKey("quadstock", "display-sides");
  private final DisplaySigns writer = new DisplaySigns(OWNER, DIGEST, SIDES);

  private static final class FakeSign {
    final List<Component> front =
        new ArrayList<>(
            List.of(
                Component.text("Original front"),
                Component.empty(),
                Component.empty(),
                Component.empty()));
    final List<Component> back =
        new ArrayList<>(
            List.of(
                Component.text("Original back"),
                Component.empty(),
                Component.empty(),
                Component.empty()));
    final Map<Object, Object> pdc = new HashMap<>();
    final Map<Side, DyeColor> colors = new EnumMap<>(Side.class);
    final Map<Side, Boolean> glow = new EnumMap<>(Side.class);
    int updates;

    FakeSign() {
      for (Side s : Side.values()) {
        colors.put(s, DyeColor.BLACK);
        glow.put(s, false);
      }
    }

    Sign snapshot() {
      var container =
          (PersistentDataContainer)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {PersistentDataContainer.class},
                  (p, m, a) ->
                      switch (m.getName()) {
                        case "get" -> pdc.get(a[0]);
                        case "has" -> pdc.containsKey(a[0]);
                        case "set" -> {
                          pdc.put(a[0], a[2]);
                          yield null;
                        }
                        case "remove" -> {
                          pdc.remove(a[0]);
                          yield null;
                        }
                        default -> throw new UnsupportedOperationException(m.getName());
                      });
      return (Sign)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {Sign.class},
              (p, m, a) ->
                  switch (m.getName()) {
                    case "getPersistentDataContainer" -> container;
                    case "getSide" -> side((Side) a[0]);
                    case "update" -> {
                      updates++;
                      yield true;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                  });
    }

    SignSide side(Side side) {
      var lines = side == Side.FRONT ? front : back;
      return (SignSide)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {SignSide.class},
              (p, m, a) ->
                  switch (m.getName()) {
                    case "lines" -> List.copyOf(lines);
                    case "line" -> {
                      if (a.length == 1) yield lines.get((Integer) a[0]);
                      lines.set((Integer) a[0], (Component) a[1]);
                      yield null;
                    }
                    case "getColor" -> colors.get(side);
                    case "setColor" -> {
                      colors.put(side, (DyeColor) a[0]);
                      yield null;
                    }
                    case "isGlowingText" -> glow.get(side);
                    case "setGlowingText" -> {
                      glow.put(side, (Boolean) a[0]);
                      yield null;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                  });
    }
  }

  private DisplayDefinition definition(FakeSign sign, String sides) {
    var d = DisplayStoreTest.draft().option("surface", "SIGN").option("sides", sides);
    var originals = new HashMap<String, String>();
    for (Side side : Side.values()) DisplaySigns.capture(originals, side, sign.snapshot());
    return d.saved(originals, false);
  }

  private List<Component> rendered() {
    return List.of(
        Component.text("CUSTOM"),
        Component.text("$123"),
        Component.text("+2%"),
        Component.text("Neutral"));
  }

  @Test
  public void mirrorsBothSidesAndRestoresOriginalFormatting() throws Exception {
    var sign = new FakeSign();
    sign.colors.put(Side.FRONT, DyeColor.BLUE);
    sign.glow.put(Side.BACK, true);
    var front = List.copyOf(sign.front);
    var back = List.copyOf(sign.back);
    var d = definition(sign, "BOTH");
    writer.render(d, sign.snapshot(), rendered(), false);
    assertEquals(sign.front, sign.back);
    assertEquals(rendered(), sign.front);
    writer.render(d, sign.snapshot(), List.of(), true);
    assertEquals(front, sign.front);
    assertEquals(back, sign.back);
    assertEquals(DyeColor.BLUE, sign.colors.get(Side.FRONT));
    assertTrue(sign.glow.get(Side.BACK));
    assertTrue(sign.pdc.isEmpty());
    writer.render(d, sign.snapshot(), List.of(), true);
    assertEquals(front, sign.front);
  }

  @Test
  public void unselectedSideEditsSurviveRefreshAndRemoval() throws Exception {
    var sign = new FakeSign();
    var d = definition(sign, "FRONT");
    writer.render(d, sign.snapshot(), rendered(), false);
    sign.back.set(0, Component.text("Administrator changed the unselected side"));
    writer.render(d, sign.snapshot(), rendered(), false);
    assertEquals(1, sign.updates);
    writer.render(d, sign.snapshot(), List.of(), true);
    assertEquals(Component.text("Administrator changed the unselected side"), sign.back.getFirst());
  }

  @Test
  public void externalChangesSuspendInsteadOfOverwriting() throws Exception {
    var sign = new FakeSign();
    var d = definition(sign, "FRONT");
    writer.render(d, sign.snapshot(), rendered(), false);
    sign.front.set(0, Component.text("External edit"));
    assertThrows(IOException.class, () -> writer.render(d, sign.snapshot(), rendered(), false));
    assertThrows(IOException.class, () -> writer.render(d, sign.snapshot(), List.of(), true));
    writer.release(d, sign.snapshot());
    assertEquals(Component.text("External edit"), sign.front.getFirst());
    assertTrue(sign.pdc.isEmpty());
  }

  @Test
  public void interruptedClaimDetectsChangedOriginalAndSideSwitchRestoresPrevious()
      throws Exception {
    var sign = new FakeSign();
    var d = definition(sign, "FRONT");
    sign.colors.put(Side.FRONT, DyeColor.RED);
    assertThrows(IOException.class, () -> writer.render(d, sign.snapshot(), rendered(), false));
    sign.colors.put(Side.FRONT, DyeColor.BLACK);
    writer.render(d, sign.snapshot(), rendered(), false);
    writer.render(d.option("sides", "BACK"), sign.snapshot(), rendered(), false);
    assertEquals(Component.text("Original front"), sign.front.getFirst());
    assertEquals(rendered(), sign.back);
  }
}
