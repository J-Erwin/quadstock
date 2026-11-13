package com.thaalur.quadstock.config;

import com.thaalur.quadstock.trading.HoldingsLedger;
import com.thaalur.quadstock.trading.TradingPolicy;
import java.io.IOException;
import java.nio.file.Path;

/** Checks settings against durable identity and settlement contracts without applying changes. */
public final class ConfigurationValidator {
  private ConfigurationValidator() {}

  public static void validate(Path directory, MarketSettings candidate, HoldingsLedger ledger)
      throws IOException {
    TabSettings.load(directory.resolve("tab.yml"));
    // Validate durable display payloads before reload detaches the active runtime.
    new com.thaalur.quadstock.display.DisplayStore(directory);
    candidate.validateSavedIdentities(directory, false);
    if (ledger != null)
      for (var e : ledger.entries()) {
        var industry = candidate.industries().get(e.intent().ticker());
        if (industry == null || !industry.market().equals(e.intent().market()))
          throw new IOException(
              "Journal ticker "
                  + e.intent().ticker()
                  + " must remain in market "
                  + e.intent().market());
      }
    var policy = TradingPolicy.load(directory);
    if (policy.isPresent())
      for (var i : candidate.industries().values())
        if (i.dailyLimit() != null
            && i.dailyLimit().stripTrailingZeros().scale() > policy.get().digits())
          throw new IOException(i.ticker() + ": payout limit exceeds currency precision");
  }
}
