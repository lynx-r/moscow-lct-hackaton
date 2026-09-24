package com.ultra.dit.controller;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ultra.dit.dto.RoutingResult;
import com.ultra.dit.exception.ParseException;
import com.ultra.dit.service.RoutingFlowService;
import com.ultra.dit.stage1.ParseService;
import com.ultra.dit.stage2.model.ClustersRoutingResult;
import com.ultra.dit.stage2_test.ProcessStage2RoutesService;

@RestController
@RequestMapping("routing")
public class RoutingController {

  private final ParseService parserService;
  private final RoutingFlowService routingFlowService;
  private final ProcessStage2RoutesService processStage2RoutesService;

  public RoutingController(ParseService parserService, RoutingFlowService routingFlowService,
      ProcessStage2RoutesService processStage2RoutesService) {
    this.parserService = parserService;
    this.routingFlowService = routingFlowService;
    this.processStage2RoutesService = processStage2RoutesService;
  }

  @PostMapping("process")
  public ResponseEntity<RoutingResult> process(@RequestParam MultipartFile file) {
    try {
      var featureCollection = parserService.streamFeatures(file.getInputStream());
      var outputGeoJson = routingFlowService.buildRoute(featureCollection);
      return ResponseEntity.ok(outputGeoJson);
    } catch (IOException e) {
      throw new ParseException("Не удалось разобрать GeoJson " + e.getMessage());
    }
  }

  @PostMapping("process-json")
  public ResponseEntity<RoutingResult> processJson(InputStream inputStream) {
    var featureCollection = parserService.streamFeatures(inputStream);
    var outputGeoJson = routingFlowService.buildRoute(featureCollection);
    return ResponseEntity.ok(outputGeoJson);
  }

  @PostMapping("process-stage2")
  public ResponseEntity<ClustersRoutingResult> processStage2(@RequestParam MultipartFile file) {
    try {
      var featureCollection = parserService.streamFeatures(file.getInputStream());
      var outputGeoJson = processStage2RoutesService.buildRoutes(featureCollection, true);
      return ResponseEntity.ok(outputGeoJson);
    } catch (IOException e) {
      throw new ParseException("Не удалось разобрать GeoJson " + e.getMessage());
    }
  }

}
