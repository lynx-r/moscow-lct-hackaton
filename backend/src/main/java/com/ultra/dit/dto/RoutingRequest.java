package com.ultra.dit.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
public class RoutingRequest {
    private FeatureCollection featureCollection;

    public RoutingRequest(FeatureCollection featureCollection) {
        this.featureCollection = featureCollection;
    }
}
