package com.ultra.dit.mock;

import com.ultra.dit.dto.RoutingRequest;
import com.ultra.dit.dto.RoutingResult;
import com.ultra.dit.service.RoutingCalculationService;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.springframework.stereotype.Service;

@Service
public class MockRoutingCalculationService implements RoutingCalculationService {

    @Override
    public RoutingResult calculate(RoutingRequest request) {
        return new RoutingResult(request.getFeatureCollection());
    }

}
