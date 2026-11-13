package com.thaalur.quadstock.ui;

import java.util.Optional;
import net.kyori.adventure.text.Component;

/** Footer ownership and pack status for one connection; never overwrites a competing writer. */
final class TabSession {
  private final Component original;
  private Component lastWritten;
  private boolean relinquished;
  boolean packRequested;
  boolean packLoaded;

  TabSession(Component original) {
    this.original = normalize(original);
  }

  Optional<Component> update(Component current, Component panel) {
    current = normalize(current);
    if (relinquished) return Optional.empty();
    if (!current.equals(lastWritten == null ? original : lastWritten)) {
      relinquished = true;
      return Optional.empty();
    }
    Component desired =
        panel.equals(Component.empty())
            ? original
            : original.equals(Component.empty())
                ? panel
                : Component.empty().append(original).append(Component.newline()).append(panel);
    if (desired.equals(current)) return Optional.empty();
    lastWritten = desired;
    return Optional.of(desired);
  }

  Optional<Component> restore(Component current) {
    return !relinquished
            && lastWritten != null
            && lastWritten.equals(normalize(current))
            && !original.equals(lastWritten)
        ? Optional.of(original)
        : Optional.empty();
  }

  private static Component normalize(Component component) {
    return component == null ? Component.empty() : component;
  }
}
