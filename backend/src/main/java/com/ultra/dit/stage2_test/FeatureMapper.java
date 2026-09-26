package com.ultra.dit.stage2_test;

import java.util.List;
import java.util.stream.Stream;

import org.locationtech.jts.geom.Geometry;

import com.ultra.dit.dto.Feature;
import com.ultra.dit.stage2.model.RoutingResult;

public class FeatureMapper {
  public static Stream<Feature> mapRoutingResultToFeatures(RoutingResult routingResult) {
    return Stream
        .concat(routingResult.getNewPipelines().stream(),
            routingResult.getChambers().stream());
  }

  public static <T extends Geometry> Stream<Feature> mapGeometriesToFeatures(List<T> geometries) {
    return geometries.stream().map(geometry -> Feature.withDefaultProperties(geometry));
  }

  public static <T extends Geometry> Stream<Feature> mapStreamGeometriesToFeatures(Stream<T> geometries) {
    return geometries.map(geometry -> Feature.withDefaultProperties(geometry));
  }
}
