package com.thaalur.quadstock.ui;

import static org.junit.Assert.*;

import java.util.UUID;
import org.junit.Test;

public class DialogSessionsTest {
  @Test
  public void buttonsShareOneResponseAndRejectReplay() {
    var sessions = new DialogSessions();
    UUID player = UUID.randomUUID();
    UUID token = sessions.begin(player);
    assertTrue(sessions.consume(player, token));
    assertFalse(sessions.consume(player, token));
  }

  @Test
  public void replacementInvalidatesOldButtonsWithoutConsumingNewDialog() {
    var sessions = new DialogSessions();
    UUID player = UUID.randomUUID();
    UUID old = sessions.begin(player);
    UUID current = sessions.begin(player);
    assertFalse(sessions.consume(player, old));
    assertTrue(sessions.consume(player, current));
  }

  @Test
  public void anotherPlayerCannotConsumeResponse() {
    var sessions = new DialogSessions();
    UUID player = UUID.randomUUID();
    UUID token = sessions.begin(player);
    assertFalse(sessions.consume(UUID.randomUUID(), token));
    assertTrue(sessions.consume(player, token));
  }

  @Test
  public void navigationOrLogoutInvalidatesOutstandingResponse() {
    var sessions = new DialogSessions();
    UUID player = UUID.randomUUID();
    UUID token = sessions.begin(player);
    assertTrue(sessions.forget(player));
    assertFalse(sessions.consume(player, token));
    assertFalse(sessions.forget(player));
  }
}
