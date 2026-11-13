package com.thaalur.quadstock.display;

import java.util.*;
import java.util.regex.Pattern;

/** Vanilla-font width budgeting and grapheme-safe, paused headline animation. */
public final class DisplayText {
  private static final Pattern GRAPHEME = Pattern.compile("\\X");

  private DisplayText() {}

  public static List<String> glyphs(String text) {
    return GRAPHEME.matcher(text).results().map(r -> r.group()).toList();
  }

  public static int width(String text, boolean bold) {
    int total = 0;
    for (var glyph : glyphs(text)) {
      int c = glyph.codePointAt(0);
      int w =
          c == ' '
              ? 4
              : "il.,:;!|'".indexOf(c) >= 0
                  ? 2
                  : "`".indexOf(c) >= 0
                      ? 3
                      : "It[]".indexOf(c) >= 0
                          ? 4
                          : "fk{}<>\"".indexOf(c) >= 0
                              ? 5
                              : "@~".indexOf(c) >= 0 ? 7 : c < 128 ? 6 : 9;
      long extras =
          glyph
              .codePoints()
              .skip(1)
              .filter(
                  cp ->
                      Character.getType(cp) != Character.NON_SPACING_MARK
                          && Character.getType(cp) != Character.COMBINING_SPACING_MARK
                          && Character.getType(cp) != Character.FORMAT)
              .count();
      total += w + (int) extras * 9 + (bold && c != ' ' ? 1 : 0);
    }
    return total;
  }

  public static String fit(String text, int pixels, boolean bold) {
    if (width(text, bold) <= pixels) return text;
    var out = new StringBuilder();
    for (String g : glyphs(text)) {
      if (width(out + g + "…", bold) > pixels) break;
      out.append(g);
    }
    return out + "…";
  }

  public static String scroll(String text, int pixels, long elapsedMs, long stepMs, long pauseMs) {
    var glyphs = glyphs(text);
    if (width(text, false) <= pixels) return text;
    int last = glyphs.size() - 1;
    while (last > 0
        && width(String.join("", glyphs.subList(last - 1, glyphs.size())), false) <= pixels) last--;
    long travel = (long) last * stepMs;
    long phase = Math.floorMod(elapsedMs, 2 * pauseMs + travel);
    int start = phase < pauseMs ? 0 : (int) Math.min(last, (phase - pauseMs) / stepMs);
    var out = new StringBuilder();
    for (int i = start; i < glyphs.size(); i++) {
      var g = glyphs.get(i);
      if (width(out + g, false) > pixels) break;
      out.append(g);
    }
    return out.toString();
  }
}
