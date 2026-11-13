package com.thaalur.quadstock.display;

import java.util.List;

/** Cardinal mounting geometry shared by saved floating displays and their private previews. */
public final class DisplayMount {
  private DisplayMount() {}

  /** Snaps to a block face, normalizing the result to [-180, 180). */
  public static float snap(double yaw) {
    if (!Double.isFinite(yaw)) throw new IllegalArgumentException("Invalid display yaw");
    double normalized = ((yaw + 180) % 360 + 360) % 360 - 180;
    int quarter = (int) Math.floor((normalized + 45) / 90);
    return Math.floorMod(quarter + 2, 4) * 90 - 180;
  }

  /** One outward-facing text plane; offsets avoid coplanar backgrounds. */
  public record Face(float yaw, double x, double z) {}

  public static List<Face> faces(double yaw) {
    float front = snap(yaw);
    return List.of(face(front), face(snap(front + 180)));
  }

  private static Face face(float yaw) {
    double radians = Math.toRadians(yaw);
    return new Face(yaw, -Math.sin(radians) * .01, Math.cos(radians) * .01);
  }
}
