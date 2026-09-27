package com.ultra.dit.service;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.ultra.dit.dto.RoutingResult;
import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.FeatureCollection;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.service.calculation.RoutingCalculationService;

import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor 
public class RoutingFlowService {
    private final RoutingCalculationService calculation;

    public RoutingResult buildRoute(Stream<? extends  Feature<? extends FeatureProperties>> features) {
        // The solver accepts and returns WGS84. It owns projection and spatial indices.
        // This initial integration materializes the input; large-file streaming remains open.
        try (features) {
            var input = FeatureCollection.of(features.collect(Collectors.toList()));
            return new RoutingResult(calculation.calculate(input));
        }
    }
}
