package com.thaalur.quadstock.display;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.*;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.junit.Test;

public class DisplayHitTargetsTest {
  @Test
  public void sneakHidesOnlyLocalTargetsAndStandingRestoresThem() {
    World world =
        proxy(
            World.class,
            (p, m, a) -> {
              if (m.getName().equals("equals")) return p == a[0];
              throw new UnsupportedOperationException(m.getName());
            });
    World otherWorld =
        proxy(
            World.class,
            (p, m, a) -> {
              if (m.getName().equals("equals")) return p == a[0];
              throw new UnsupportedOperationException(m.getName());
            });
    Interaction local = target(world);
    Interaction remote = target(otherWorld);
    List<String> calls = new ArrayList<>();
    Player player =
        proxy(
            Player.class,
            (p, m, a) -> {
              if (m.getName().equals("getWorld")) return world;
              if (m.getName().equals("hideEntity") || m.getName().equals("showEntity")) {
                assertSame(local, a[1]);
                calls.add(m.getName());
                return null;
              }
              throw new UnsupportedOperationException(m.getName());
            });
    DisplayHitTargets.visibility(null, player, List.of(local, remote), true);
    DisplayHitTargets.visibility(null, player, List.of(local, remote), false);
    assertEquals(List.of("hideEntity", "showEntity"), calls);
  }

  private Interaction target(World world) {
    return proxy(
        Interaction.class,
        (p, m, a) -> {
          if (m.getName().equals("getWorld")) return world;
          throw new UnsupportedOperationException(m.getName());
        });
  }

  private <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return type.cast(
        Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {type}, handler));
  }
}
