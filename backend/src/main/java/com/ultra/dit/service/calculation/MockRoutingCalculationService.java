package com.ultra.dit.service.calculation;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.ultra.dit.dto.FeatureCollection;

@Primary
@Service("mockRoutingCalculationService")
public class MockRoutingCalculationService implements RoutingCalculationService {

    @Override
    public FeatureCollection calculate(FeatureCollection request) {
        return request;
    }

}
