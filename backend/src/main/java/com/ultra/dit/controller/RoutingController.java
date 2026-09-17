package com.ultra.dit.controller;

import java.io.IOException;

import org.locationtech.jts.geom.GeometryCollection;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ultra.dit.exception.ParseException;
import com.ultra.dit.service.RoutingService;

@RestController
@RequestMapping("routing")
public class RoutingController {

  private final RoutingService routingService;

  public RoutingController(RoutingService routingService) {
    this.routingService = routingService;
  }

  @PostMapping("process")
  public ResponseEntity<GeometryCollection> process(@RequestParam MultipartFile file) {
    try {
      var outputGeoJson = routingService.processRouting(file.getInputStream());
      return ResponseEntity.ok(outputGeoJson);
    } catch (IOException e) {
      throw new ParseException("Не удалось разобрать GeoJson " + e.getMessage());
    }

  }

}
