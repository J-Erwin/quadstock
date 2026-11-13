package com.thaalur.quadstock.trading;

import java.math.BigDecimal;
import java.util.Locale;

/** Dollar-style trade amounts, preserving exact decimal terms without conversion to double. */
public final class CurrencyFormat {
  private CurrencyFormat() {}

  /** Formats an amount with grouping and at least two decimal places, preserving precision. */
  public static String exact(BigDecimal amount) {
    return String.format(Locale.US, "$%,." + Math.max(2, amount.scale()) + "f", amount);
  }
}
