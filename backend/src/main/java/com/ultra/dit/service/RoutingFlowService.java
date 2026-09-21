package com.ultra.dit.service;

import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.FeatureCollection;
import com.ultra.dit.dto.RoutingResult;
import com.ultra.dit.service.calculation.RoutingCalculationService;

@Service
public class RoutingFlowService {

  private final TransformService transformService;
  private final RoutingCalculationService routingCalculationService;
  private final SpatialIndexService spatialIndexService;

  RoutingFlowService(TransformService transformService,
      RoutingCalculationService routingProcessService, SpatialIndexService spatialIndexService) {
    this.transformService = transformService;
    this.routingCalculationService = routingProcessService;
    this.spatialIndexService = spatialIndexService;
  }

  public RoutingResult buildRoute(Stream<Feature> featureCollection) {
    var readyCollection = transformService.transform4326To32637(featureCollection);

    var indexContainer = spatialIndexService.buildSpatialIndices(readyCollection);

    // var calculatedCollection =
    // routingCalculationService.calculate(readyCollection);

    // var resultCollection =
    // transformService.transform32637To4326(readyCollection);

    return new RoutingResult(new FeatureCollection());
  }
}
