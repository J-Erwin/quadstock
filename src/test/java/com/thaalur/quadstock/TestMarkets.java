package com.thaalur.quadstock;

import com.thaalur.quadstock.config.MarketSettings.Industry;
import java.util.Set;

/** Explicit fixture settings for tests that do not exercise configuration loading. */
public final class TestMarkets {
  private TestMarkets() {}

  public static Industry industry() {
    return new Industry(
        "AGRI",
        "OVERWORLD",
        "Test Company",
        "WHEAT",
        Set.of("WHEAT", "CARROT", "POTATO", "BEETROOT"),
        100,
        1,
        true,
        true,
        null);
  }
}
