package com.ultra.dit.stage2.graph;

import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

import com.ultra.dit.stage2.model.OksObject;
import com.ultra.dit.stage2.service.CollisionDetectionService;

/**
 * Модернизированный калькулятор весов ребер (Шаг 2.3 + Шаг 2.4).
 * Учитывает как углы поворота, так и физическую проходимость 2D-буфера в
 * пространстве.
 */
public class IntegratedEdgeWeightCalculator {

  private final EdgeWeightCalculator angleCalculator;
  private final CollisionDetectionService collisionService;
  private final List<Geometry> localConstraints;
  private final List<OksObject> activeCluster;
  private final GeometryFactory geometryFactory = new GeometryFactory();

  public IntegratedEdgeWeightCalculator(
      CollisionDetectionService collisionService,
      List<Geometry> localConstraints,
      List<OksObject> activeCluster) {
    this.angleCalculator = new EdgeWeightCalculator();
    this.collisionService = collisionService;
    this.localConstraints = localConstraints;
    this.activeCluster = activeCluster;
  }

  public double calculateWeight(Coordinate p1, Coordinate p2, Coordinate p3) {
    // 1. Первичная проверка угла поворота и базовой стоимости (длины)
    double baseWeight = angleCalculator.calculateWeight(p1, p2, p3);
    if (baseWeight == Double.MAX_VALUE) {
      return Double.MAX_VALUE;
    }

    // 2. Геометрическая сборка отрезка для проверки коллизий
    LineString segment = geometryFactory.createLineString(new Coordinate[] {
        p1, p2, p3
    });

    // 3. Проверка 2D буферизации с защитой от самоблокирования
    boolean passable = collisionService.isSegmentPassable(segment, localConstraints, activeCluster);
    if (!passable) {
      return Double.MAX_VALUE; // Полностью блокируем ребро при нарушении ограничений
    }

    return baseWeight;
  }
}
