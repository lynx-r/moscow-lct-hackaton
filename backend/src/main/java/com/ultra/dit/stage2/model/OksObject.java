package com.ultra.dit.stage2.model;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.OksConnectionPointProperties;

import lombok.Value;

/**
 * Исходный объект ОКС (Потребитель)
 * 
 * OksObject
 */
@Value
public class OksObject {
  private final Feature<OksConnectionPointProperties> feature;
  private final Geometry buildingPolygon;

  public static OksObject of(Feature<OksConnectionPointProperties> feature, Geometry buildingPolygon) {
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