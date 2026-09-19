package com.ultra.dit.service;

import java.io.InputStream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.ultra.dit.dto.FeatureCollection;
import com.ultra.dit.dto.RoutingRequest;
import com.ultra.dit.dto.RoutingResult;
import org.locationtech.jts.geom.GeometryCollection;
import org.springframework.stereotype.Service;

@Service
public class RoutingFlowService {

  private final ParseService parserService;
  private final TransformService transformService;
  private final RoutingProcessService routingProcessService;
  private final RoutingCalculationService routingCalculationService;
  private final GeoJsonService geoJsonService;

  public RoutingFlowService(ParseService parserService, TransformService transformService,
      RoutingProcessService routingProcessService, RoutingCalculationService routingCalculationService, GeoJsonService geoJsonService) {
    this.parserService = parserService;
    this.transformService = transformService;
    this.routingProcessService = routingProcessService;
    this.routingCalculationService = routingCalculationService;
    this.geoJsonService = geoJsonService;
  }

  public JsonNode buildRoute(InputStream inputStream) throws JsonProcessingException {
    FeatureCollection featureCollection = parserService.parse(inputStream);

    FeatureCollection transformed = transformService.toUtm37n(featureCollection);
    RoutingRequest request = new RoutingRequest(transformed);

    RoutingResult result = routingCalculationService.calculate(request);

    FeatureCollection output = transformService.toWgs84(result.getFeatureCollection());
    return geoJsonService.toGeoJson(output);
  }
}
