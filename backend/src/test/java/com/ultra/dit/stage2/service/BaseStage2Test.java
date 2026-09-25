package com.ultra.dit.stage2.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;

import com.ultra.dit.BaseTest;
import com.ultra.dit.dto.FeatureCollection;
import com.ultra.dit.stage1.ParseService;

public abstract class BaseStage2Test extends BaseTest {

  protected static final ParseService PARSE_SERVICE = new ParseService(OBJECT_MAPPER);

  protected static FeatureCollection testFeatureCollection;
  protected static FeatureCollection organizerFeatureCollection;

  @BeforeAll
  public static void initAllTests() throws IOException {
    assertTrue(datasetBaseResource.exists(), "Файл датасета должен существовать");

    // Поток откроется, прочитается и закроется ровно один раз
    try (var datasetInputStream = datasetBaseResource.getInputStream();
        var originFeatureStream = PARSE_SERVICE.streamFeatures(datasetInputStream)) {
      var features = originFeatureStream.collect(Collectors.toList());
      testFeatureCollection = FeatureCollection.of(features);
    }
  }
}
