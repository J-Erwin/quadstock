package com.thaalur.quadstock.runtime;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ReloadOperationTest {
  @Test
  public void validatesAndCheckpointsBeforeReplacingRuntime() throws Exception {
    var calls = new ArrayList<String>();
    ReloadOperation.run(
        () -> calls.add("validate"),
        () -> calls.add("save"),
        () -> calls.add("stop"),
        () -> calls.add("start"),
        () -> calls.add("disable"));
    assertEquals(List.of("validate", "save", "stop", "start"), calls);
  }

  @Test
  public void invalidConfigurationLeavesActiveRuntimeUntouched() {
    var calls = new ArrayList<String>();
    assertThrows(
        IOException.class,
        () ->
            ReloadOperation.run(
                () -> {
                  throw new IOException("bad config");
                },
                () -> calls.add("save"),
                () -> calls.add("stop"),
                () -> calls.add("start"),
                () -> calls.add("disable")));
    assertTrue(calls.isEmpty());
  }

  @Test
  public void failuresDisableWithoutRetryOrLaterPhases() {
    for (int failAt = 0; failAt < 3; failAt++) {
      final int failure = failAt;
      var calls = new ArrayList<String>();
      ReloadOperation.Step[] steps = new ReloadOperation.Step[3];
      for (int i = 0; i < 3; i++) {
        final int phase = i;
        steps[i] =
            () -> {
              calls.add("phase" + phase);
              if (phase == failure) throw new IOException("failure");
            };
      }
      assertThrows(
          IOException.class,
          () ->
              ReloadOperation.run(
                  () -> {}, steps[0], steps[1], steps[2], () -> calls.add("disable")));
      assertEquals(failAt + 2, calls.size());
      assertEquals("disable", calls.getLast());
    }
  }
}
