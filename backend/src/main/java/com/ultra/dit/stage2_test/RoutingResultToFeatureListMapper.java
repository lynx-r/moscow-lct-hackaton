package com.ultra.dit.stage2_test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.FeatureProperties;
import com.ultra.dit.stage2.model.RoutingResult;

public class RoutingResultToFeatureListMapper {
  public static List<Feature> map(RoutingResult routingResult) {
    return Stream
        .concat(routingResult.getNewPipelines().stream().map((pipe) -> Feature.of(pipe, new FeatureProperties())),
            routingResult.getChambers().stream().map((chamber) -> Feature.of(chamber, new FeatureProperties())))
        .collect(Collectors.toList());
  }
}
