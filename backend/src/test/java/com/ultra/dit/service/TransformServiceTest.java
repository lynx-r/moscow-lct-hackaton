package com.ultra.dit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class TransformServiceTest extends AbstractGeometryTest {

  @InjectMocks
  private TransformService transformService;

  @Test
  void testTransformFeatureCollection() {
    // 1. Прямой перевод 4326 -> 32637
    var transformed4326To32637 = transformService.transform4326To32637(originGeometryCollection);
    assertNotNull(transformed4326To32637);
    assertEquals(32637, transformed4326To32637.getSRID());

    // 2. Обратный перевод 32637 -> 4326
    var transformed32637To4326 = transformService.transform32637To4326(transformed4326To32637);
    assertNotNull(transformed32637To4326);
    assertEquals(4326, transformed32637To4326.getSRID());

    // 3. Каноническая ГИС-проверка на равенство с погрешностью (например, 10 знаков
    // после запятой)
    double tolerance = 1E-10;
    boolean isExactlyEqual = originGeometryCollection.equalsExact(transformed32637To4326, tolerance);

    assertTrue(isExactlyEqual, "Исходная и обратно-трансформированная геометрии не совпадают в пределах допуска");
  }
}
