package com.ultra.dit.service;

import org.locationtech.jts.geom.GeometryCollection;
import org.springframework.stereotype.Service;

@Service
public class RoutingProcessService {

  public GeometryCollection execute(GeometryCollection epsg32637Collection) {
    return epsg32637Collection;
  }
}
