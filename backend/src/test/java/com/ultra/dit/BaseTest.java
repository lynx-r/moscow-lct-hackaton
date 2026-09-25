package com.ultra.dit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.mockito.Spy;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ultra.dit.dto.Feature;
import com.ultra.dit.service.DebugUtils;
import com.ultra.dit.stage1.ParseService;

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

  protected static List<List<Feature>> testFeatures;

  protected static List<Feature> featuresBase;
  protected static List<Feature> featuresOrganizer;

  @BeforeAll
  public static void initAllTests() throws IOException {
    testFeatures = datasetResources.stream().map((resource) -> {
      assertTrue(resource.exists(), "Файл " + resource.getFilename() + " должен существовать");

      // Поток откроется, прочитается и закроется ровно один раз
      try (var datasetInputStream = resource.getInputStream()) {
        var featureStream = PARSE_SERVICE.streamFeatures(datasetInputStream).collect(Collectors.toList());
        return featureStream;
      } catch (IOException e) {
        e.printStackTrace();
        return List.<Feature>of();
      }
    }).collect(Collectors.toList());

    // datasetTestResource
    assertTrue(datasetBaseResource.exists(), "Файл датасета должен существовать");

    // Поток откроется, прочитается и закроется ровно один раз
    try (var datasetInputStream = datasetBaseResource.getInputStream()) {
      featuresBase = PARSE_SERVICE.streamFeatures(datasetInputStream).collect(Collectors.toList());
    }

    // datasetOrganizerResource
    assertTrue(datasetOrganizerResource.exists(), "Файл датасета должен существовать");

    // Поток откроется, прочитается и закроется ровно один раз
    try (var datasetInputStream = datasetOrganizerResource.getInputStream()) {
      featuresOrganizer = PARSE_SERVICE.streamFeatures(datasetInputStream).collect(Collectors.toList());
    }
  }

}
