package com.ultra.dit.dto;

import org.locationtech.jts.geom.Geometry;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Data
@NoArgsConstructor
@RequiredArgsConstructor(staticName = "of")
public class Feature {
    @NonNull
    private Geometry geometry;

    @NonNull
    private FeatureProperties properties;

    private final GeoJsonType type = GeoJsonType.FEATURE;

    public static Feature withDefaultProperties(Geometry geometry) {
        return Feature.of(geometry, new FeatureProperties());
    }
}
