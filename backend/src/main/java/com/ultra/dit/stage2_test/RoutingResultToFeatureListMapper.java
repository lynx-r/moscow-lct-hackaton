package com.ultra.dit.stage2_test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.GeoJsonType;
import com.ultra.dit.stage2.model.RoutingResult;

public class RoutingResultToFeatureListMapper {
  public static List<Feature> map(RoutingResult routingResult) {
    return Stream
        .concat(routingResult.getNewPipelines().stream().map((pipe) -> new Feature(pipe, null, GeoJsonType.FEATURE)),
            routingResult.getChambers().stream().map((chamber) -> new Feature(chamber, null, GeoJsonType.FEATURE)))
        .collect(Collectors.toList());
  }
}
