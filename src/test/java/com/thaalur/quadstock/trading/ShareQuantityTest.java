package com.thaalur.quadstock.trading;

import static org.junit.Assert.*;

import org.junit.Test;

public class ShareQuantityTest {
  @Test
  public void acceptsCustomWholeSharesWithinConfiguredLimit() {
    assertEquals(37, ShareQuantity.parse("37", 10000));
    assertEquals(10000, ShareQuantity.parse("10000", 10000));
  }

  @Test
  public void rejectsInvalidAndOverflowWithoutTruncation() {
    for (String text :
        new String[] {
          "0",
          "-1",
          "+2",
          "1.5",
          "1e3",
          "1,000",
          "2147483648",
          "99999999999999999999",
          "",
          " 3",
          "101"
        }) assertThrows(text, IllegalArgumentException.class, () -> ShareQuantity.parse(text, 100));
  }
}
