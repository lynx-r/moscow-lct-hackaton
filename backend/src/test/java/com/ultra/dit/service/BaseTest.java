package com.ultra.dit.service;

import org.locationtech.jts.geom.GeometryCollection;
import org.n52.jackson.datatype.jts.JtsModule;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.databind.ObjectMapper;

public abstract class BaseTest {
  // Общие объекты для всех тестов геометрии
  protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JtsModule());
  protected static final ParseService PARSE_SERVICE = new ParseService(OBJECT_MAPPER);

  // Кэшированная геометрия — распарсится один раз на все тесты!
  protected static GeometryCollection originGeometryCollection;

  protected static final ClassPathResource datasetResource = new ClassPathResource("files/dataset-15.09.26.geojson");
}
