package com.ultra.dit.stage1;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.BeforeEach;

import com.ultra.dit.BaseTest;

public abstract class BaseStage1Test extends BaseTest {

  protected InputStream datasetBaseInputStream;
  protected InputStream datasetOrganizerInputStream;

  @BeforeEach
  public void setUp() throws IOException {
    datasetBaseInputStream = datasetBaseResource.getInputStream();
    datasetOrganizerInputStream = datasetOrganizerResource.getInputStream();
  }

}
