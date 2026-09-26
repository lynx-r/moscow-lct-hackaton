package com.ultra.dit.service.calculation;

import com.ultra.dit.dto.geojson.FeatureCollection;

public interface RoutingCalculationService {

    FeatureCollection calculate(FeatureCollection request);
}
