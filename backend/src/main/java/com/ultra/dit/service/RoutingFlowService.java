package com.ultra.dit.service;

import org.springframework.stereotype.Service;

import com.ultra.dit.dto.FeatureCollection;
import com.ultra.dit.dto.RoutingResult;
import com.ultra.dit.service.calculation.RoutingCalculationService;

@Service
public class RoutingFlowService {

  private final TransformService transformService;
  private final RoutingCalculationService routingCalculationService;

  RoutingFlowService(TransformService transformService,
      RoutingCalculationService routingProcessService) {
    this.transformService = transformService;
    this.routingCalculationService = routingProcessService;
  }

  public RoutingResult buildRoute(FeatureCollection featureCollection) {
    var readyCollection = transformService.transform4326To32637(featureCollection);

    var calculatedCollection = routingCalculationService.calculate(readyCollection);

    var resultCollection = transformService.transform32637To4326(calculatedCollection);

    return new RoutingResult(resultCollection);
  }
}
