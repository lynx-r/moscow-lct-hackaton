package com.ultra.dit.service;

import java.io.InputStream;

import org.locationtech.jts.geom.GeometryCollection;
import org.springframework.stereotype.Service;

@Service
public class RoutingService {

  private final TransformService transformService;
  private final ParserService parserService;

  RoutingService(TransformService transformService, ParserService parserService) {
    this.transformService = transformService;
    this.parserService = parserService;
  }

  public GeometryCollection processRouting(InputStream inputStream) throws Exception {
    var featureCollection = parserService.parseFeatureCollection(inputStream);

    var transformedCollection = transformService.transformCollection(featureCollection);
    return transformedCollection;
  }
}
