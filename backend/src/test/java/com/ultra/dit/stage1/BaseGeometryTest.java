package com.ultra.dit.stage1;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

import com.ultra.dit.dto.FeatureCollection;
import com.ultra.dit.dto.GeoJsonType;

public abstract class BaseGeometryTest extends BaseStage1Test {

  // Кэшированная геометрия — распарсится один раз на все тесты!
  protected static FeatureCollection originFeatureCollection;

  @BeforeAll
  public static void initAllTests() throws IOException {
    assertTrue(datasetResource.exists(), "Файл датасета должен существовать");

    // Поток откроется, прочитается и закроется ровно один раз
    try (var datasetInputStream = datasetResource.getInputStream()) {
      var originFeatureStream = PARSE_SERVICE.streamFeatures(datasetInputStream);
      var features = originFeatureStream.collect(Collectors.toList());
      originFeatureCollection = FeatureCollection.of(features);
    }
  }

  @BeforeEach
  public void setUp() {
    originFeatureStream = originFeatureCollection.getFeatures().stream();
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
