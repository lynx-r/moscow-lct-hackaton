package com.ultra.dit.controller;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.ultra.dit.dto.FeatureCollection;
import org.locationtech.jts.geom.GeometryCollection;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ultra.dit.exception.ParseException;
import com.ultra.dit.service.RoutingFlowService;

@RestController
@RequestMapping("routing")
public class RoutingController {

  private final RoutingFlowService routingFlowService;

  public RoutingController(RoutingFlowService routingFlowService) {
    this.routingFlowService = routingFlowService;
  }

  @PostMapping(value = "process")
  public ResponseEntity<JsonNode> process(@RequestParam MultipartFile file) {
    try {
      var outputGeoJson = routingFlowService.buildRoute(file.getInputStream());
      return ResponseEntity.ok(outputGeoJson);
    } catch (IOException e) {
      throw new ParseException("Не удалось разобрать GeoJson " + e.getMessage());
    }
  }

}
