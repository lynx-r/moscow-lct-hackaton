package com.ultra.dit.stage1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ParseServiceTest extends BaseStage1Test {

  @InjectMocks
  private ParseService parseService;

  private InputStream datasetInputStream;

  @BeforeEach
  public void setUp() throws IOException {
    datasetInputStream = datasetResource.getInputStream();
  }

  @Test
  void testParseFeatureCollection() {
    var parsed = parseService.streamFeatures(datasetInputStream);
    var features = parsed.collect(Collectors.toList());
    assertNotNull(features);
    assertEquals(features.size(), 144);
  }
}
