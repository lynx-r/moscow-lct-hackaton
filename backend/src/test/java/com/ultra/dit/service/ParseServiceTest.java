package com.ultra.dit.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.n52.jackson.datatype.jts.JtsModule;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
public class ParseServiceTest {

  @Spy
  private ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JtsModule()); // Регистрируем ваш JtsModule

  @InjectMocks
  private ParseService parseService;

  private InputStream datasetInputStream;

  @BeforeEach
  public void setUp() throws IOException {
    var datasetResource = new ClassPathResource("files/dataset-15.09.26.geojson");
    assert datasetResource.exists();

    datasetInputStream = datasetResource.getInputStream();
  }

  @Test
  void testParseFeatureCollection() {
    var parsed = parseService.parseFeatureCollection(datasetInputStream);
    assertNotNull(parsed);
  }
}
