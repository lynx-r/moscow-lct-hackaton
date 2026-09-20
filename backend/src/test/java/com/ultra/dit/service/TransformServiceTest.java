package com.ultra.dit.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class TransformServiceTest extends BaseGeometryTest {

  @InjectMocks
  private TransformService transformService;

  @Test
  void testTransformFeatureCollection() {
    // 1. Прямой перевод 4326 -> 32637
    var transformed4326To32637 = transformService.transform4326To32637(originFeatureCollection);
    assertNotNull(transformed4326To32637);

    // 2. Обратный перевод 32637 -> 4326
    var transformed32637To4326 = transformService.transform32637To4326(transformed4326To32637);
    assertNotNull(transformed32637To4326);

    double tolerance = 1E-10;
    assertFeatureGeometriesNotEqual(transformed4326To32637, transformed32637To4326, tolerance);
    assertFeatureGeometriesEqual(originFeatureCollection, transformed32637To4326, tolerance);
  }

}
