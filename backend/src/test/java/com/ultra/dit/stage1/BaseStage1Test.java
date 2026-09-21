package com.ultra.dit.stage1;

import java.util.stream.Stream;

import com.ultra.dit.BaseTest;
import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.FeatureCollection;

public abstract class BaseStage1Test extends BaseTest {

  protected static final ParseService PARSE_SERVICE = new ParseService(OBJECT_MAPPER);

  // Кэшированная геометрия — распарсится один раз на все тесты!
  protected static FeatureCollection originFeatureCollection;

  protected static Stream<Feature> originFeatureStream;
}
