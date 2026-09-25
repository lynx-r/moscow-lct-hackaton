package com.ultra.dit.stage2.model;

import org.locationtech.jts.geom.Coordinate;

import com.ultra.dit.dto.FeatureProperties;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Виртуальная вершина графа (Учитывает Coordinate + Вектор Захода)
 * 
 * VirtualNode
 */
@Getter
@RequiredArgsConstructor(staticName = "of")
public class VirtualNode {
  private final Coordinate coordinate;
  private final NodeType type;
  private final FeatureProperties featureProperties;
  private final OksObject oksObject;

  public boolean isOksObject() {
    return oksObject != null;
  }

  public boolean hasFeatureProperties() {
    return featureProperties != null;
  }

  public static VirtualNode withFeatureProperties(Coordinate coordinate, NodeType type,
      FeatureProperties featureProperties) {
    return VirtualNode.of(coordinate, type, featureProperties, null);
  }

  public static VirtualNode withOksObject(Coordinate coordinate, NodeType type,
      OksObject oksObject) {
    return VirtualNode.of(coordinate, type, null, oksObject);
  }

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