package com.ultra.dit.service;

import java.io.InputStream;

import org.locationtech.jts.geom.GeometryCollection;
import org.springframework.stereotype.Service;

@Service
public class RoutingService {

  private final TransformService transformService;
  private final ParseService parserService;

  RoutingService(TransformService transformService, ParseService parserService) {
    this.transformService = transformService;
    this.parserService = parserService;
  }

  public GeometryCollection processRouting(InputStream inputStream) {
    var featureCollection = parserService.parseFeatureCollection(inputStream);

    var transformedCollection = transformService.transformCollection(featureCollection);
    return transformedCollection;
  }
}
