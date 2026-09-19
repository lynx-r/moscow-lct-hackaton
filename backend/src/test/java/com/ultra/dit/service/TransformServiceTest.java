package com.ultra.dit.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultra.dit.dto.FeatureCollection;

@ExtendWith(MockitoExtension.class)
public class TransformServiceTest extends BaseGeometryTest {

  @InjectMocks
  private TransformService transformService;

  @Test
  void testTransformFeatureCollection() {
    // 1. Прямой перевод 4326 -> 32637
    var transformed4326To32637 = transformService.transform4326To32637(originFeatureCollection);
    assertNotNull(transformed4326To32637);
    // assertEquals(32637, transformed4326To32637.getSRID());

    // 2. Обратный перевод 32637 -> 4326
    var transformed32637To4326 = transformService.transform32637To4326(transformed4326To32637);
    assertNotNull(transformed32637To4326);
    // assertEquals(4326, transformed32637To4326.getSRID());

    double tolerance = 1E-10;
    assertFeatureGeometriesEqual(originFeatureCollection, transformed32637To4326, tolerance);
  }

  public void assertFeatureGeometriesEqual(FeatureCollection expected, FeatureCollection actual, double tolerance) {
    GeometryFactory factory = new GeometryFactory();

    // Собираем массивы геометрий
    Geometry[] expectedGeoms = expected.getFeatures().stream().map(t -> t.getGeometry()).toArray(Geometry[]::new);
    Geometry[] actualGeoms = actual.getFeatures().stream().map(t -> t.getGeometry()).toArray(Geometry[]::new);

    // Создаем две GeometryCollection
    var expCollection = factory.createGeometryCollection(expectedGeoms);
    var actCollection = factory.createGeometryCollection(actualGeoms);

    // Сравниваем старым методом
    assertTrue(expCollection.equalsExact(actCollection, tolerance),
        "Геометрии коллекций не совпадают в пределах допуска");
  }
}
