package com.thaalur.quadstock.runtime;

/** Orders reload phases so validation failure leaves the current runtime untouched. */
public final class ReloadOperation {
  private ReloadOperation() {}

  @FunctionalInterface
  public interface Step {
    void run() throws Exception;
  }

  /** Checkpoint or replacement failures fail closed; no payment or startup is retried. */
  public static void run(Step validate, Step checkpoint, Step stop, Step start, Runnable disable)
      throws Exception {
    validate.run();
    try {
      checkpoint.run();
      stop.run();
      start.run();
    } catch (Exception failure) {
      try {
        disable.run();
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }
}
