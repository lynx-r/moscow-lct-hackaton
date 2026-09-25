package com.ultra.dit.stage1;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultra.dit.service.RoutingFlowService;
import com.ultra.dit.service.calculation.MockRoutingCalculationService;
import com.ultra.dit.service.calculation.RoutingCalculationService;

@ExtendWith(MockitoExtension.class) // Подключаем Mockito к JUnit 5
public class RoutingServiceTest extends BaseGeometryTest {

  @Spy
  private ParseService parserService = new ParseService(OBJECT_MAPPER);

  @Spy
  private TransformService transformService = new TransformService();

  @Spy
  private SpatialIndexService spatialIndexService = new SpatialIndexService(OKS_CONNECTION_POINT_BUFFER_METERS);

  @Spy
  private RoutingCalculationService routingCalculationService = new MockRoutingCalculationService();

  @InjectMocks
  private RoutingFlowService routingService;

  @Test
  void testBuildRoute() throws IOException {
    for (var resource : datasetResources) {
      var featuresInputStream = resource.getInputStream();
      var featuresStream = parserService.streamFeatures(featuresInputStream);
      var parsed = routingService.buildRoute(featuresStream);
      assertNotNull(parsed);
    }
  }

  @Test
  void shouldBaseProcessRouting() {
    var result = routingService.buildRoute(featuresBase.stream());
    assertNotNull(result);
  }

  @Test
  void shouldOrganizerProcessRouting() {
    var result = routingService.buildRoute(featuresOrganizer.stream());
    assertNotNull(result);
  }

}
