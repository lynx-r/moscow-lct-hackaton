package com.ultra.dit.service;

import com.ultra.dit.dto.FeatureCollection;
import org.locationtech.jts.geom.GeometryCollection;
import org.springframework.stereotype.Service;

@Service
public class RoutingProcessService {

  public FeatureCollection execute(FeatureCollection epsg32637Collection) {

    return epsg32637Collection;
  }
}
