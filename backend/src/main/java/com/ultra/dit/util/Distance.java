package com.ultra.dit.util;

import java.util.Comparator;

import org.locationtech.jts.geom.Coordinate;

public class Distance {
  private static final double COORDINATE_COMPARATOR_EPSILON = 1E-6; // Максимальное отклонение по X или Y

  public static double distanceSq(Coordinate c1, Coordinate c2) {
    double dx = c1.x - c2.x;
    double dy = c1.y - c2.y;
    return dx * dx + dy * dy;
  }

  public static Comparator<? super Coordinate> CoordinateComparator = (c1, c2) -> {
    // ВАЖНО: COORDINATE_COMPARATOR_EPSILON должен быть, например, 1E-6
    // Округляем до фиксированной сетки (например, делением на эпсилон)
    long x1 = Math.round(c1.x / COORDINATE_COMPARATOR_EPSILON);
    long x2 = Math.round(c2.x / COORDINATE_COMPARATOR_EPSILON);

    if (x1 != x2) {
      return Long.compare(x1, x2);
    }

    long y1 = Math.round(c1.y / COORDINATE_COMPARATOR_EPSILON);
    long y2 = Math.round(c2.y / COORDINATE_COMPARATOR_EPSILON);

    return Long.compare(y1, y2);
  };
}
