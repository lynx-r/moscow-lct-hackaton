package com.ultra.dit;

import org.mockito.Spy;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ultra.dit.service.DebugUtils;

public abstract class BaseTest {
  // Общие объекты для всех тестов геометрии
  protected static final ObjectMapper OBJECT_MAPPER = DebugUtils.OBJECT_MAPPER;

  @Spy
  protected final ObjectMapper objectMapper = OBJECT_MAPPER;

  protected static final ClassPathResource datasetResource = new ClassPathResource("files/dataset-19.09.26.geojson");

  protected final double OKS_CONNECTION_POINT_BUFFER_METERS = 2.0;

}
