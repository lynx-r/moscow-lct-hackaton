package com.ultra.dit.stage1;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

import com.ultra.dit.dto.Feature;

public abstract class BaseGeometryTest extends BaseStage1Test {

  void assertFeatureGeometriesEqual(List<Feature> expected, List<Feature> actual, double tolerance) {
    assertTrue(isGeometriesEqual(expected, actual, tolerance), "Геометрии коллекций не совпадают в пределах допуска");
  }

  void assertFeatureGeometriesNotEqual(List<Feature> expected, List<Feature> actual, double tolerance) {
    assertFalse(isGeometriesEqual(expected, actual, tolerance), "Геометрия не изменилась");
  }

  boolean isGeometriesEqual(List<Feature> expected, List<Feature> actual, double tolerance) {
    GeometryFactory factory = new GeometryFactory();

    // Собираем массивы геометрий
    Geometry[] expectedGeoms = expected.stream().map(t -> t.getGeometry()).toArray(Geometry[]::new);
    Geometry[] actualGeoms = actual.stream().map(t -> t.getGeometry()).toArray(Geometry[]::new);

    // Создаем две GeometryCollection
    var expCollection = factory.createGeometryCollection(expectedGeoms);
    var actCollection = factory.createGeometryCollection(actualGeoms);

    return expCollection.equalsExact(actCollection, tolerance);
  }
}
