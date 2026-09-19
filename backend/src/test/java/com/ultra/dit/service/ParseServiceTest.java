package com.ultra.dit.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ParseServiceTest extends BaseTest {

  @InjectMocks
  private ParseService parseService;

  private InputStream datasetInputStream;

  @BeforeEach
  public void setUp() throws IOException {
    datasetInputStream = datasetResource.getInputStream();
  }

  @Test
  void testParseFeatureCollection() {
    var parsed = parseService.parseFeatureCollection(datasetInputStream);
    assertNotNull(parsed);
  }
}
