package com.ultra.dit.stage2.model;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import lombok.Value;

/**
 * Исходный объект ОКС (Потребитель)
 * 
 * OksObject
 */
@Value
public class OksObject {
  private final String id;
  private final Point connectionPoint;
  private final Geometry buildingPolygon;
  private final Double flowTph;
}