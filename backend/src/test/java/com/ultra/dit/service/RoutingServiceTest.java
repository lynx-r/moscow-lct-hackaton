package com.ultra.dit.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultra.dit.service.calculation.MockRoutingCalculationService;
import com.ultra.dit.service.calculation.RoutingCalculationService;

@ExtendWith(MockitoExtension.class) // Подключаем Mockito к JUnit 5
public class RoutingServiceTest extends BaseGeometryTest {

  @Spy
  private ParseService parserService = new ParseService(OBJECT_MAPPER);

  @Spy
  private TransformService transformService = new TransformService();

  @Spy
  private RoutingCalculationService routingCalculationService = new MockRoutingCalculationService();

  @InjectMocks
  private RoutingFlowService routingService;

  @Test
  void shouldProcessRouting() {
    var result = routingService.buildRoute(originFeatureCollection);
    assertNotNull(result);
  }

}
