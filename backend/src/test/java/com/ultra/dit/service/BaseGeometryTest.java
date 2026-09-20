package com.ultra.dit.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

import com.ultra.dit.dto.FeatureCollection;

public abstract class BaseGeometryTest extends BaseTest {

  @BeforeAll
  public static void initAllTests() throws IOException {
    assertTrue(datasetResource.exists(), "Файл датасета должен существовать");

    // Поток откроется, прочитается и закроется ровно один раз
    try (var datasetInputStream = datasetResource.getInputStream()) {
      originFeatureCollection = PARSE_SERVICE.parseFeatureCollection(datasetInputStream);
    }
  }

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
