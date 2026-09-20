package com.ultra.dit.controller;

import java.io.IOException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ultra.dit.dto.FeatureCollection;
import com.ultra.dit.dto.RoutingResult;
import com.ultra.dit.exception.ParseException;
import com.ultra.dit.service.ParseService;
import com.ultra.dit.service.RoutingFlowService;

@RestController
@RequestMapping("routing")
public class RoutingController {

  private final ParseService parserService;
  private final RoutingFlowService routingFlowService;

  public RoutingController(ParseService parserService, RoutingFlowService routingFlowService) {
    this.parserService = parserService;
    this.routingFlowService = routingFlowService;
  }

  @PostMapping("process")
  public ResponseEntity<RoutingResult> process(@RequestParam MultipartFile file) {
    try {
      var featureCollection = parserService.parseFeatureCollection(file.getInputStream());
      var outputGeoJson = routingFlowService.buildRoute(featureCollection);
      return ResponseEntity.ok(outputGeoJson);
    } catch (IOException e) {
      throw new ParseException("Не удалось разобрать GeoJson " + e.getMessage());
    }
  }

  // @PostMapping("process-json")
  // public ResponseEntity<RoutingResult> processJson(@RequestBody
  // FeatureCollection featureCollection) {
  // var outputGeoJson = routingFlowService.buildRoute(featureCollection);
  // return ResponseEntity.ok(outputGeoJson);
  // }

}
