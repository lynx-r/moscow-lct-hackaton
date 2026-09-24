package com.ultra.dit.stage1;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

import com.ultra.dit.dto.FeatureCollection;

public abstract class BaseGeometryTest extends BaseStage1Test {

  void assertFeatureGeometriesEqual(FeatureCollection expected, FeatureCollection actual, double tolerance) {
    assertTrue(isGeometriesEqual(expected, actual, tolerance), "Геометрии коллекций не совпадают в пределах допуска");
  }

  void assertFeatureGeometriesNotEqual(FeatureCollection expected, FeatureCollection actual, double tolerance) {
    assertFalse(isGeometriesEqual(expected, actual, tolerance), "Геометрия не изменилась");
  }

  boolean isGeometriesEqual(FeatureCollection expected, FeatureCollection actual, double tolerance) {
    GeometryFactory factory = new GeometryFactory();

    // Собираем массивы геометрий
    Geometry[] expectedGeoms = expected.getFeatures().stream().map(t -> t.getGeometry()).toArray(Geometry[]::new);
    Geometry[] actualGeoms = actual.getFeatures().stream().map(t -> t.getGeometry()).toArray(Geometry[]::new);

    // Создаем две GeometryCollection
    var expCollection = factory.createGeometryCollection(expectedGeoms);
    var actCollection = factory.createGeometryCollection(actualGeoms);

    return expCollection.equalsExact(actCollection, tolerance);
  }
}
