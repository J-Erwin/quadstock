package com.thaalur.quadstock.command;

import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.command.CommandSender;
import org.junit.Test;

public class ReloadCommandTest {
  @Test
  public void unauthorizedAndMalformedRequestsCannotReload() {
    var allowed = new AtomicBoolean(false);
    var calls = new AtomicInteger();
    var messages = new ArrayList<String>();
    CommandSender sender =
        (CommandSender)
            Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("hasPermission")) {
                    assertEquals("quadstock.admin", args[0]);
                    return allowed.get();
                  }
                  if (method.getName().equals("sendMessage")) {
                    messages.add((String) args[0]);
                    return null;
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    ReloadCommand.execute(sender, new String[] {"reload"}, calls::incrementAndGet);
    assertEquals(0, calls.get());
    allowed.set(true);
    ReloadCommand.execute(sender, new String[] {"reload", "extra"}, calls::incrementAndGet);
    assertEquals(0, calls.get());
    assertEquals("Usage: /qm reload", messages.getLast());
    ReloadCommand.execute(sender, new String[] {"reload"}, calls::incrementAndGet);
    assertEquals(1, calls.get());
    assertTrue(messages.getLast().contains("reloaded"));
    ReloadCommand.execute(
        sender,
        new String[] {"reload"},
        () -> {
          throw new IOException("bad config");
        });
    assertTrue(messages.getLast().contains("failed"));
    allowed.set(false);
    ReloadCommand.execute(sender, new String[] {"reload"}, calls::incrementAndGet);
    assertEquals(1, calls.get());
  }
}
