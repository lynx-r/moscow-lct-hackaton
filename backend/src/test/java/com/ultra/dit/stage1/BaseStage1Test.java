package com.ultra.dit.stage1;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import com.ultra.dit.BaseTest;
import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.FeatureCollection;

public abstract class BaseStage1Test extends BaseTest {

  protected static final ParseService PARSE_SERVICE = new ParseService(OBJECT_MAPPER);

  // Кэшированная геометрия — распарсится один раз на все тесты!
  protected static FeatureCollection originFeatureCollection;

  protected static Stream<Feature> originFeatureStream;

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
}
