package com.ultra.dit.service;

import java.io.InputStream;

import org.locationtech.jts.geom.GeometryCollection;
import org.springframework.stereotype.Service;

@Service
public class RoutingFlowService {

  private final ParseService parserService;
  private final TransformService transformService;
  private final RoutingProcessService routingProcessService;

  RoutingFlowService(ParseService parserService, TransformService transformService,
      RoutingProcessService routingProcessService) {
    this.parserService = parserService;
    this.transformService = transformService;
    this.routingProcessService = routingProcessService;
  }

  public GeometryCollection buildRoute(InputStream inputStream) {
    var featureCollection = parserService.parseFeatureCollection(inputStream);

    var epsg32637Collection = transformService.transformCollection(featureCollection);

    return routingProcessService.execute(epsg32637Collection);
  }
}
