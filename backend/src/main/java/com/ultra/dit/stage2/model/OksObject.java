package com.ultra.dit.stage2.model;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import com.ultra.dit.dto.Feature;

import lombok.Value;

/**
 * Исходный объект ОКС (Потребитель)
 * 
 * OksObject
 */
@Value
public class OksObject {
  private final Feature feature;
  private final Geometry buildingPolygon;

  public static OksObject of(Feature feature, Geometry buildingPolygon) {
    return new OksObject(feature, buildingPolygon);
  }

  public String getId() {
    return feature.getProperties().getId();
  }

  public Point getConnectionPoint() {
    return (Point) feature.getGeometry();
  }

  public double getFlowTph() {
    return feature.getProperties().getFlowTph();
  }
}