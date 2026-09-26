package com.ultra.dit.dto;

import com.ultra.dit.dto.geojson.FeatureCollection;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoutingRequest {
    private FeatureCollection featureCollection;
}
