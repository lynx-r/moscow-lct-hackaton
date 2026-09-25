package com.ultra.dit.stage1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ParseServiceTest extends BaseStage1Test {

  @InjectMocks
  private ParseService parseService;

  @Test
  void testParseService() throws IOException {
    for (var resource : datasetResources) {
      var featuresInputStream = resource.getInputStream();
      var parsed = parseService.streamFeatures(featuresInputStream);
      var features = parsed.collect(Collectors.toList());
      assertFalse(features.isEmpty());
    }
  }

  @Test
  void testBaseParseFeatureCollection() {
    var parsed = parseService.streamFeatures(datasetBaseInputStream);
    var features = parsed.collect(Collectors.toList());
    assertNotNull(features);
    assertEquals(features.size(), 6);
  }

  @Test
  void testOrganizerParseFeatureCollection() {
    var parsed = parseService.streamFeatures(datasetOrganizerInputStream);
    var features = parsed.collect(Collectors.toList());
    assertNotNull(features);
    assertEquals(features.size(), 144);
  }
}
