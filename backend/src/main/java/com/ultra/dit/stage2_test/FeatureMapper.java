package com.ultra.dit.stage2_test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.locationtech.jts.geom.Geometry;

import com.ultra.dit.dto.Feature;
import com.ultra.dit.stage2.model.RoutingResult;

public class FeatureMapper {
  public static List<Feature> mapRoutingResultToFeatures(RoutingResult routingResult) {
    return Stream
        .concat(mapGeometriesToFeatures(routingResult.getNewPipelines()),
            mapGeometriesToFeatures(routingResult.getChambers()))
        .collect(Collectors.toList());
  }

  public static <T extends Geometry> Stream<Feature> mapGeometriesToFeatures(List<T> geometries) {
    return geometries.stream().map(geometry -> Feature.withDefaultProperties(geometry));
  }

  public static <T extends Geometry> Stream<Feature> mapStreamGeometriesToFeatures(Stream<T> geometries) {
    return geometries.map(geometry -> Feature.withDefaultProperties(geometry));
  }
}
