package com.ultra.dit.dto;

import org.locationtech.jts.geom.Geometry;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Feature {
    private Geometry geometry;
    private FeatureProperties properties;
    private GeoJsonType type;
}
