package com.ultra.dit.stage2.model;

import org.locationtech.jts.geom.Coordinate;

import com.ultra.dit.constants.FeaturePropertiesStyles;
import com.ultra.dit.dto.geojson.FeatureProperties;
import com.ultra.dit.dto.geojson.GraphEdgeProperties;
import com.ultra.dit.dto.geojson.ObjectType;
import com.ultra.dit.dto.geojson.OksConnectionPointProperties;
import com.ultra.dit.factory.FeaturePropertiesStyleFactory;

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
      FeatureProperties properties) {
    if (properties == null) {
      properties = new FeatureProperties();
    }

    var styleProperties = FeaturePropertiesStyleFactory.getStyleProperties(properties.getAdditionalProperties(),
        properties.getObjectType());
    properties.addAdditionalProperties(styleProperties);

    return VirtualNode.of(coordinate, type, properties, null);
  }

  public static VirtualNode withOksObject(Coordinate coordinate, NodeType type,
      OksObject oksObject) {
    OksConnectionPointProperties properties = oksObject.getFeature().getProperties();
    return VirtualNode.of(coordinate, type, properties, oksObject);
  }

  public static VirtualNode asVoronoiSkeleton(Coordinate coordinate) {
    var properties = new GraphEdgeProperties();
    properties.setId("edge-" + coordinate.hashCode());
    properties.addAdditionalProperties(FeaturePropertiesStyles.VORONOI_SKELETON);
    properties.setObjectType(ObjectType.GRAPH_EDGE);
    return VirtualNode.of(coordinate, NodeType.VORONOI_SKELETON, properties, null);
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