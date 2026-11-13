package com.thaalur.quadstock.display;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;

/** Both readable faces form one disposable projection of a single registered display. */
public final class FloatingText {
  private final List<TextDisplay> faces;

  public FloatingText(TextDisplay front, TextDisplay back) {
    faces = List.of(front, back);
  }

  public boolean owns(Entity entity) {
    return faces.contains(entity);
  }

  public boolean isValid() {
    return faces.stream().allMatch(Entity::isValid);
  }

  public void text(Component content) {
    for (TextDisplay face : faces) if (!face.text().equals(content)) face.text(content);
  }

  public void remove() {
    faces.forEach(Entity::remove);
  }
}
