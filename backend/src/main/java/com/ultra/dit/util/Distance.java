package com.ultra.dit.util;

import org.locationtech.jts.geom.Coordinate;

public class Distance {
  public static double distanceSq(Coordinate c1, Coordinate c2) {
    double dx = c1.x - c2.x;
    double dy = c1.y - c2.y;
    return dx * dx + dy * dy;
  }
}
