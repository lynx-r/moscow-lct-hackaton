package com.ultra.dit.service.calculation;

import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.ultra.dit.dto.FeatureCollection;

@Primary
@ConditionalOnProperty(name = "app.routing.backend", havingValue = "mock")
@Service("mockRoutingCalculationService")
public class MockRoutingCalculationService implements RoutingCalculationService {

    @Override
    public FeatureCollection calculate(FeatureCollection request) {
        return request;
    }

}
