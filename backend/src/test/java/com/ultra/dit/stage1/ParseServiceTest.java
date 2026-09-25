package com.ultra.dit.stage1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import com.ultra.dit.aop.LoggingAspect;

public class ParseServiceTest extends BaseStage1Test {

  private ParseService parseService;

  @BeforeEach
  void setUp() {
    var parseServiceSpy = Mockito.spy(new ParseService(OBJECT_MAPPER));
    AspectJProxyFactory parseServiceFactory = new AspectJProxyFactory(parseServiceSpy);
    parseServiceFactory.addAspect(new LoggingAspect());
    parseServiceFactory.setProxyTargetClass(true);
    parseService = parseServiceFactory.getProxy();
  }

  @Test
  void testParseService() throws IOException {
    for (var resource : datasetResources) {
      try (var featuresInputStream = resource.getInputStream();
          var parsed = parseService.streamFeatures(featuresInputStream)) {
        var features = parsed.collect(Collectors.toList());
        assertFalse(features.isEmpty());
      }
    }
  }

  @Test
  void testBaseParseFeatureCollection() {
    try (var parsed = parseService.streamFeatures(datasetBaseInputStream)) {
      var features = parsed.collect(Collectors.toList());
      assertNotNull(features);
      assertEquals(features.size(), 6);
    }
  }

  @Test
  void testOrganizerParseFeatureCollection() {
    try (var parsed = parseService.streamFeatures(datasetOrganizerInputStream)) {
      var features = parsed.collect(Collectors.toList());
      assertNotNull(features);
      assertEquals(features.size(), 144);
    }
  }
}
