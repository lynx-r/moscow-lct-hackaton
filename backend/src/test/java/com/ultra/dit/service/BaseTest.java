package com.ultra.dit.service;

import org.mockito.Spy;
import org.n52.jackson.datatype.jts.JtsModule;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ultra.dit.dto.FeatureCollection;

public abstract class BaseTest {
  @Spy
  protected ObjectMapper objectMapper = OBJECT_MAPPER;

  // Общие объекты для всех тестов геометрии
  protected static final ObjectMapper OBJECT_MAPPER = new Jackson2ObjectMapperBuilder().failOnUnknownProperties(false)
      .modules(new JtsModule()).build();
  protected static final ParseService PARSE_SERVICE = new ParseService(OBJECT_MAPPER);

  // Кэшированная геометрия — распарсится один раз на все тесты!
  protected static FeatureCollection originFeatureCollection;

  protected static final ClassPathResource datasetResource = new ClassPathResource("files/dataset-15.09.26.geojson");
}
