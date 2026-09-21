package com.ultra.dit.stage1;

import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    var transformed4326To32637Stream = transformService.transform4326To32637(originFeatureStream);
    var transformed4326To32637 = transformed4326To32637Stream.collect(Collectors.toList());

    // 2. Обратный перевод 32637 -> 4326
    var transformed32637To4326Stream = transformService.transform32637To4326(transformed4326To32637.stream());
    var transformed32637To4326 = transformed32637To4326Stream.collect(Collectors.toList());

    var transformed4326To32637Collection = new FeatureCollection(transformed4326To32637, "FeatureCollection");
    var transformed32637To4326Collection = new FeatureCollection(transformed32637To4326, "FeatureCollection");

    double tolerance = 1E-10;
    assertFeatureGeometriesNotEqual(transformed4326To32637Collection, transformed32637To4326Collection, tolerance);
    assertFeatureGeometriesEqual(originFeatureCollection,
        transformed32637To4326Collection, tolerance);
  }

}
