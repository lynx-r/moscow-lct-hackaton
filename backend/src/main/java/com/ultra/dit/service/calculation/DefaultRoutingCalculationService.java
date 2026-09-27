package com.ultra.dit.service.calculation;



import com.ultra.dit.dto.geojson.FeatureCollection;

/** Historical identity implementation; not registered as a routing backend. */
public class DefaultRoutingCalculationService implements RoutingCalculationService {

    @Override
    public FeatureCollection calculate(FeatureCollection request) {
        return request;
    }

}
