package com.thaalur.quadstock.security;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

public class PermissionsTest {
  @Test
  public void checksCurrentPermissionRatherThanCachingAnEarlierGrant() {
    var allowed = new AtomicBoolean(true);
    var messages = new AtomicInteger();
    CommandSender sender =
        (CommandSender)
            Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("hasPermission")) return allowed.get();
                  if (method.getName().equals("sendMessage")) {
                    messages.incrementAndGet();
                    return null;
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    assertTrue(Permissions.require(sender, Permissions.TRADE));
    allowed.set(false);
    assertFalse(Permissions.require(sender, Permissions.TRADE));
    assertEquals(1, messages.get());
  }

  @Test
  public void standardPlayersAndAdministratorBundleHaveExplicitDefaults() throws Exception {
    var config = new YamlConfiguration();
    config.load(Path.of("src/main/resources/plugin.yml").toFile());
    for (String node : new String[] {Permissions.USE, Permissions.TRADE, Permissions.REPORTS}) {
      assertEquals(true, config.get("permissions." + node + ".default"));
      assertEquals(true, config.get("permissions.quadstock.player.children." + node));
    }
    assertEquals("op", config.get("permissions.quadstock.admin.default"));
    assertEquals(
        true, config.get("permissions.quadstock.admin.children.quadstock.commerce.inspect"));
  }
}
