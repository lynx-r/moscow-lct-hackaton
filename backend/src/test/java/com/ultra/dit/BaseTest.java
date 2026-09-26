package com.ultra.dit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.stage1.ParseService;
import com.ultra.dit.util.DebugUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

@ExtendWith(MockitoExtension.class)
public abstract class BaseTest {
  // Общие объекты для всех тестов геометрии
  protected static final ObjectMapper OBJECT_MAPPER = DebugUtils.OBJECT_MAPPER;

  @Spy
  protected final ObjectMapper objectMapper = OBJECT_MAPPER;

  protected static final ParseService PARSE_SERVICE = new ParseService(OBJECT_MAPPER);

  private static final List<String> PATH_RESOURCES = List.of("files/dataset-19.09.26.geojson",
      "files/1-test-dataset.geojson");

  protected static final List<ClassPathResource> datasetResources = PATH_RESOURCES.stream()
      .map(path -> new ClassPathResource(path)).collect(Collectors.toList());

  protected static final ClassPathResource datasetOrganizerResource = new ClassPathResource(
      "files/dataset-19.09.26.geojson");
  protected static final ClassPathResource datasetBaseResource = new ClassPathResource("files/1-test-dataset.geojson");

  protected final double OKS_CONNECTION_POINT_BUFFER_METERS = 2.0;

  protected static List<List<Feature<? extends FeatureProperties>>> testFeatures;

  protected static List<Feature<? extends FeatureProperties>> featuresBase;
  protected static List<Feature<? extends FeatureProperties>> featuresOrganizer;

  @BeforeAll
  public static void initAllTests() throws IOException {
    LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
    loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.DEBUG);

    testFeatures = new ArrayList<>();

    for (var resource : datasetResources) {
      assertTrue(resource.exists(), "Файл " + resource.getFilename() + " должен существовать");

      // Открываем поток, полностью вычитываем стрим сервиса в список, и только потом
      // закрываем поток
      try (var datasetInputStream = resource.getInputStream();
          var featureStream = PARSE_SERVICE.streamFeatures(datasetInputStream)) {

        List<Feature<? extends FeatureProperties>> features = featureStream.collect(Collectors.toList());
        testFeatures.add(features);
      }
    }

    // datasetTestResource
    assertTrue(datasetBaseResource.exists(), "Файл датасета должен существовать");

    // Поток откроется, прочитается и закроется ровно один раз
    try (var datasetInputStream = datasetBaseResource.getInputStream();
        var featureStream = PARSE_SERVICE.streamFeatures(datasetInputStream)) {
      featuresBase = featureStream.collect(Collectors.toList());
    }

    // datasetOrganizerResource
    assertTrue(datasetOrganizerResource.exists(), "Файл датасета должен существовать");

    // Поток откроется, прочитается и закроется ровно один раз
    try (var datasetInputStream = datasetOrganizerResource.getInputStream();
        var featureStream = PARSE_SERVICE.streamFeatures(datasetInputStream)) {
      featuresOrganizer = featureStream.collect(Collectors.toList());
    }
  }

}
