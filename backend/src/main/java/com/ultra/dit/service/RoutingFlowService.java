package com.ultra.dit.service;

import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.ultra.dit.dto.RoutingResult;
import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.FeatureCollection;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.stage1.SpatialIndexService;
import com.ultra.dit.stage1.TransformService;

@Service
public class RoutingFlowService {

  private final TransformService transformService;
  private final SpatialIndexService spatialIndexService;

  public RoutingFlowService(TransformService transformService,
      SpatialIndexService spatialIndexService) {
    this.transformService = transformService;
    this.spatialIndexService = spatialIndexService;
  }

  public RoutingResult buildRoute(Stream<Feature<? extends FeatureProperties>> featureCollection) {
    var readyCollection = transformService.transform4326To32637(featureCollection);

    var indexContainer = spatialIndexService.buildSpatialIndices(readyCollection);

    // var calculatedCollection =
    // routingCalculationService.calculate(readyCollection);

    // var resultCollection =
    // transformService.transform32637To4326(readyCollection);

    return new RoutingResult(new FeatureCollection());
  }
}
