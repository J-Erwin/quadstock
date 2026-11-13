package com.thaalur.quadstock.display;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.TextDisplay;
import org.junit.Test;

public class DisplayMountTest {
  @Test
  public void everyAcceptedAngleAlignsToBlockAxes() {
    for (int yaw = -180; yaw <= 180; yaw++) {
      float snapped = DisplayMount.snap(yaw);
      assertEquals(0, snapped % 90, 0);
      double distance = Math.abs(snapped - yaw);
      distance = Math.min(distance, 360 - distance);
      assertTrue(distance <= 45);
    }
    assertEquals(90, DisplayMount.snap(-270), 0);
    assertEquals(-90, DisplayMount.snap(270), 0);
    assertEquals(0, DisplayMount.snap(17.5), 0);
    assertEquals(90, DisplayMount.snap(88), 0);
    assertEquals(-180, DisplayMount.snap(179), 0);
    assertThrows(IllegalArgumentException.class, () -> DisplayMount.snap(Double.NaN));
  }

  @Test
  public void planesFaceOppositeWaysAndStayCenteredOverTheAnchor() {
    for (double yaw : List.of(-180.0, -90.0, 0.0, 90.0, 37.0)) {
      var faces = DisplayMount.faces(yaw);
      assertEquals(2, faces.size());
      var front = faces.getFirst();
      var back = faces.getLast();
      assertEquals(180, Math.abs(front.yaw() - back.yaw()), 0);
      assertEquals(0, front.x() + back.x(), 1e-12);
      assertEquals(0, front.z() + back.z(), 1e-12);
      assertEquals(.01, Math.hypot(front.x(), front.z()), 1e-12);
      assertEquals(
          0,
          front.x() * Math.cos(Math.toRadians(front.yaw()))
              + front.z() * Math.sin(Math.toRadians(front.yaw())),
          1e-12);
    }
  }

  @Test
  public void commandsAndLegacyDefinitionsNormalizeYawWithoutChangingPlacement() throws Exception {
    var original = DisplayStoreTest.draft().option("x", "1.25").option("height", "2.5");
    var options = new HashMap<>(original.options());
    options.put("yaw", "32.75");
    var legacy =
        new DisplayDefinition(
            original.id(), original.anchor(), 7, options, original.originals(), false);
    assertEquals(0, legacy.number("yaw"), 0);
    assertEquals(1.25, legacy.number("x"), 0);
    assertEquals(2.5, legacy.number("height"), 0);
    var read =
        DisplayStore.decode(DisplayStore.encode(Map.of(legacy.id(), legacy))).get(legacy.id());
    assertEquals(legacy, read);
    assertEquals(-90, legacy.option("yaw", "-104").number("yaw"), 0);
  }

  private static final class Face {
    Component text = Component.text("Old");
    boolean valid = true;
    int writes, removals;

    TextDisplay entity() {
      return (TextDisplay)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {TextDisplay.class},
              (p, m, a) ->
                  switch (m.getName()) {
                    case "text" -> {
                      if (a == null || a.length == 0) yield text;
                      text = (Component) a[0];
                      writes++;
                      yield null;
                    }
                    case "isValid" -> valid;
                    case "remove" -> {
                      valid = false;
                      removals++;
                      yield null;
                    }
                    case "equals" -> p == a[0];
                    case "hashCode" -> System.identityHashCode(p);
                    default -> throw new UnsupportedOperationException(m.getName());
                  });
    }
  }

  @Test
  public void twoFacesRefreshAndCleanUpAsOneProjection() {
    var front = new Face();
    var back = new Face();
    var a = front.entity();
    var b = back.entity();
    var projection = new FloatingText(a, b);
    assertTrue(projection.owns(a));
    assertTrue(projection.owns(b));
    assertTrue(projection.isValid());
    projection.text(Component.text("Updated headline"));
    assertEquals(front.text, back.text);
    projection.text(Component.text("Updated headline"));
    assertEquals(1, front.writes);
    assertEquals(1, back.writes);
    back.valid = false;
    assertFalse(projection.isValid());
    projection.remove();
    assertEquals(1, front.removals);
    assertEquals(1, back.removals);
    assertFalse(front.valid);
  }
}
