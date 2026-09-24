package com.ultra.dit.stage2.model;

import org.locationtech.jts.geom.Coordinate;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Виртуальная вершина графа (Учитывает Coordinate + Вектор Захода)
 * 
 * VirtualNode
 */
@Getter
@AllArgsConstructor
public class VirtualNode {
  private final Coordinate coordinate;
  private final NodeType type;

  private static final double tolerance = 0.001;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof VirtualNode)) {
      return false;
    }
    VirtualNode that = (VirtualNode) o;
    return coordinate.equals2D(that.coordinate);
  }

  @Override
  public int hashCode() {
    return coordinate.hashCode();
  }
}