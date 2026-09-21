package com.ultra.dit;

import org.n52.jackson.datatype.jts.JtsModule;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

public abstract class BaseTest {
  // Общие объекты для всех тестов геометрии
  protected static final ObjectMapper OBJECT_MAPPER = new Jackson2ObjectMapperBuilder().failOnUnknownProperties(false)
      .modules(new JtsModule()).build();

  protected static final ClassPathResource datasetResource = new ClassPathResource("files/dataset-19.09.26.geojson");
}
