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

import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class) // Подключаем Mockito к JUnit 5
public class RoutingServiceTest extends BaseTest {

  private InputStream datasetInputStream;

  @Spy
  private ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JtsModule()); // Регистрируем ваш JtsModule

  @Spy
  private ParseService parserService = new ParseService(objectMapper);

  @Spy
  private TransformService transformService = new TransformService();

  @Spy
  private RoutingProcessService routingProcessService = new RoutingProcessService();

  @InjectMocks
  private RoutingFlowService routingService;

  @BeforeEach
  public void setUp() throws IOException {

    datasetInputStream = datasetResource.getInputStream();
  }

  @Test
  void shouldProcessRouting() {
    var result = routingService.buildRoute(datasetInputStream);
    assertNotNull(result);
  }

}
