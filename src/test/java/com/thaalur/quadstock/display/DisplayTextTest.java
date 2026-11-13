package com.thaalur.quadstock.display;

import static org.junit.Assert.*;

import org.junit.Test;

public class DisplayTextTest {
  @Test
  public void shortHeadlineStaysStillAndLongHeadlinePauses() {
    assertEquals("Short", DisplayText.scroll("Short", 90, 10000, 200, 2000));
    String text = "A long headline about harvest demand";
    assertEquals(
        DisplayText.scroll(text, 60, 0, 200, 2000), DisplayText.scroll(text, 60, 1999, 200, 2000));
    assertNotEquals(
        DisplayText.scroll(text, 60, 0, 200, 2000), DisplayText.scroll(text, 60, 3000, 200, 2000));
  }

  @Test
  public void noSurrogateOrCombiningSequenceIsSplit() {
    String text = "😀e\u0301 headline 👩‍🌾 news";
    assertTrue(DisplayText.glyphs(text).contains("e\u0301"));
    assertTrue(DisplayText.glyphs(text).contains("👩‍🌾"));
    for (int t = 0; t < 10000; t += 100) {
      String view = DisplayText.scroll(text, 40, t, 100, 500);
      assertTrue(DisplayText.width(view, false) <= 40);
      assertFalse(view.startsWith("\u0301"));
    }
  }

  @Test
  public void boldWidthAndEllipsisStayWithinBudget() {
    assertTrue(DisplayText.width("Market", true) > DisplayText.width("Market", false));
    String fitted = DisplayText.fit("A very long industry name", 60, true);
    assertTrue(fitted.endsWith("…"));
    assertTrue(DisplayText.width(fitted, true) <= 60);
  }
}
