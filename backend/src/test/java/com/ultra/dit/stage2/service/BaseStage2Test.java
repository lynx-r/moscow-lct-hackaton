package com.ultra.dit.stage2.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.core.io.ClassPathResource;

import com.ultra.dit.BaseTest;
import com.ultra.dit.dto.FeatureCollection;
import com.ultra.dit.stage1.ParseService;

public abstract class BaseStage2Test extends BaseTest {

  protected static final ParseService PARSE_SERVICE = new ParseService(OBJECT_MAPPER);

  // protected static final ClassPathResource datasetResource = new
  // ClassPathResource("files/dataset-19.09.26.geojson");
  protected static final ClassPathResource datasetResource = new ClassPathResource("files/1-test-dataset.geojson");

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
}
