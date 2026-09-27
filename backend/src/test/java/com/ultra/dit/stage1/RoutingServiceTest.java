package com.ultra.dit.stage1;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Подключаем Mockito к JUnit 5
public class RoutingServiceTest extends BaseGeometryTest {

  // private ParseService parseService;

  // private TransformService transformService;

  // private SpatialIndexService spatialIndexService;

  // private RoutingFlowService routingService;

  @BeforeEach
  void setUp() {
    // Mockito.when(properties.getOksConnectionPointBufferMeters()).thenReturn(OKS_CONNECTION_POINT_BUFFER_METERS);

    // var parseServiceSpy = Mockito.spy(new ParseService(OBJECT_MAPPER));
    // AspectJProxyFactory parseServiceFactory = new
    // AspectJProxyFactory(parseServiceSpy);
    // parseServiceFactory.addAspect(new LoggingAspect());
    // parseServiceFactory.setProxyTargetClass(true);
    // parseService = parseServiceFactory.getProxy();

    // var transformServiceSpy = Mockito.spy(new TransformService());
    // AspectJProxyFactory transformServiceFactory = new
    // AspectJProxyFactory(transformServiceSpy);
    // transformServiceFactory.addAspect(new LoggingAspect());
    // transformServiceFactory.setProxyTargetClass(true);
    // transformService = transformServiceFactory.getProxy();

    // var spatialIndexServiceSpy = Mockito.spy(new
    // SpatialIndexService(properties));
    // AspectJProxyFactory spatialIndexServiceFactory = new
    // AspectJProxyFactory(spatialIndexServiceSpy);
    // spatialIndexServiceFactory.addAspect(new LoggingAspect());
    // spatialIndexServiceFactory.setProxyTargetClass(true);
    // spatialIndexService = spatialIndexServiceFactory.getProxy();

    // routingService = new RoutingFlowService(transformService,
    // spatialIndexService);
  }

  @Test
  void testBuildRoute() throws IOException {
    // for (var resource : datasetResources) {
    // try (var featuresInputStream = resource.getInputStream();
    // var parsed = parseService.streamFeatures(featuresInputStream)) {
    // var routes = routingService.buildRoute(parsed);
    // assertNotNull(routes);
    // }
    // }
  }

  @Test
  void shouldBaseProcessRouting() {
    // var result = routingService.buildRoute(featuresBase.stream());
    // assertNotNull(result);
  }

  @Test
  void shouldOrganizerProcessRouting() {
    // var result = routingService.buildRoute(featuresOrganizer.stream());
    // assertNotNull(result);
  }

}
