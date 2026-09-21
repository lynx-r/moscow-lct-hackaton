package com.ultra.dit.stage2.model;

import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

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
  private final Polygon buildingPolygon;
  private final double flowTph;
}