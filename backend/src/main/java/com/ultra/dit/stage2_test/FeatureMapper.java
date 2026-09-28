package com.ultra.dit.stage2_test;

import java.util.List;
import java.util.stream.Stream;

import org.locationtech.jts.geom.Geometry;

import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.stage2.model.RoutingResult;

public class FeatureMapper {
  public static Stream<Feature<? extends FeatureProperties>> mapRoutingResultToFeatures(RoutingResult routingResult) {
    var summaryProperties = routingResult.getSummaryProperties();
    var summaryStream = Stream.of(Feature.asSummary(summaryProperties));
    return Stream
        .of(routingResult.getNewPipelines().stream(),
            routingResult.getChambers().stream(), summaryStream)
        .flatMap(s -> s);
  }

  public static <T extends Geometry> Stream<Feature<? extends FeatureProperties>> mapGeometriesToFeatures(
      List<T> geometries) {
    return geometries.stream().map(geometry -> Feature.withDefaultProperties(geometry));
  }

  public static <T extends Geometry> Stream<Feature<? extends FeatureProperties>> mapStreamGeometriesToFeatures(
      Stream<T> geometries) {
    return geometries.map(geometry -> Feature.withDefaultProperties(geometry));
  }

}
