package com.ultra.dit.service;

import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.FeatureCollection;
import com.ultra.dit.dto.IndexContainer;
import com.ultra.dit.dto.RoutingResult;
import com.ultra.dit.stage1.SpatialIndexService;
import com.ultra.dit.stage1.TransformService;
import com.ultra.dit.stage2.NetworkRoutingService;

@Service
public class RunRoutingToStage2Service {

  private final TransformService transformService;
  private final NetworkRoutingService networkRoutingService;
  private final SpatialIndexService spatialIndexService;

  RunRoutingToStage2Service(TransformService transformService,
      SpatialIndexService spatialIndexService, NetworkRoutingService networkRoutingService) {
    this.transformService = transformService;
    this.networkRoutingService = networkRoutingService;
    this.spatialIndexService = spatialIndexService;
  }

  public RoutingResult buildRoute(Stream<Feature> featureCollection) {
    var readyCollection = transformService.transform4326To32637(featureCollection);

    IndexContainer indexContainer = spatialIndexService.buildSpatialIndices(readyCollection);

    networkRoutingService.executeRoutingPipeline(null, indexContainer);

    // var calculatedCollection =
    // routingCalculationService.calculate(readyCollection);

    // var resultCollection =
    // transformService.transform32637To4326(readyCollection);

    return new RoutingResult(new FeatureCollection());
  }
}
