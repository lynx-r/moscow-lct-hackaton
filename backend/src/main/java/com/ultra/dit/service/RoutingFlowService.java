package com.ultra.dit.service;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.FeatureCollection;
import com.ultra.dit.dto.RoutingResult;
import com.ultra.dit.service.calculation.RoutingCalculationService;

@Service
public class RoutingFlowService {
    private final RoutingCalculationService calculation;

    public RoutingFlowService(RoutingCalculationService calculation) {
        this.calculation = calculation;
    }

    public RoutingResult buildRoute(Stream<Feature> features) {
        // The solver accepts and returns WGS84. It owns projection and spatial indices.
        // This initial integration materializes the input; large-file streaming remains open.
        try (features) {
            var input = new FeatureCollection(features.collect(Collectors.toList()), "FeatureCollection");
            return new RoutingResult(calculation.calculate(input));
        }
    }
}
