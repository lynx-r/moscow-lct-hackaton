package com.ultra.dit.service.calculation;

import org.springframework.stereotype.Service;

import com.ultra.dit.dto.geojson.FeatureCollection;

@Service("defaultRoutingCalculationService")
public class DefaultRoutingCalculationService implements RoutingCalculationService {

    @Override
    public FeatureCollection calculate(FeatureCollection request) {
        return request;
    }

}
