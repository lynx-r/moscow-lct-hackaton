package com.ultra.dit.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;

public abstract class AbstractGeometryTest extends BaseTest {

  @BeforeAll
  public static void initAllTests() throws IOException {
    assertTrue(datasetResource.exists(), "Файл датасета должен существовать");

    // Поток откроется, прочитается и закроется ровно один раз
    try (var datasetInputStream = datasetResource.getInputStream()) {
      originGeometryCollection = PARSE_SERVICE.parseFeatureCollection(datasetInputStream);
    }
  }
}
