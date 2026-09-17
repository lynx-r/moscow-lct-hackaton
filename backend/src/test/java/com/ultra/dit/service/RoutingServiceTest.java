package com.ultra.dit.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

@ExtendWith(MockitoExtension.class) // Подключаем Mockito к JUnit 5
public class RoutingServiceTest {

  private InputStream datasetInputStream;

  @InjectMocks
  private RoutingService routingService;

  @Mock
  private ParseService parserService;

  @Mock
  private TransformService transformService;

  @BeforeEach
  public void setUp() throws IOException {
    var datasetResource = new ClassPathResource("files/dataset-15.09.26.geojson");
    assert datasetResource.exists();

    datasetInputStream = datasetResource.getInputStream();
  }

  @Test
  void shouldProcessRouting() {
    var result = routingService.processRouting(datasetInputStream);
    assertNotNull(result);
  }

}
