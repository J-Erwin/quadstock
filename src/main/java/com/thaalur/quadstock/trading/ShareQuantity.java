package com.thaalur.quadstock.trading;

/** Validates custom whole-share input without accepting signs, decimals or overflow. */
public final class ShareQuantity {
  private ShareQuantity() {}

  public static int parse(String input, int maximum) {
    if (input == null || !input.matches("[0-9]{1,10}"))
      throw new IllegalArgumentException("Enter a whole number from 1 to " + maximum + ".");
    try {
      int shares = Integer.parseInt(input);
      if (shares < 1 || shares > maximum) throw new NumberFormatException();
      return shares;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("Enter a whole number from 1 to " + maximum + ".");
    }
  }
}
