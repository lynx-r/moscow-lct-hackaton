package com.ultra.dit.dto.geojson;

import org.locationtech.jts.geom.Geometry;

import com.ultra.dit.dto.geojson.enums.GeoJsonType;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.dto.geojson.properties.VariantSummaryProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Feature<T extends FeatureProperties> {
    private Geometry geometry;

    @NonNull
    private T properties;

    private final GeoJsonType type = GeoJsonType.FEATURE;

    public static <T extends FeatureProperties> Feature<? extends FeatureProperties> of(Geometry geometry,
            T properties) {
        return new Feature<T>(geometry, properties);
    }

    public static Feature<? extends FeatureProperties> withDefaultProperties(Geometry geometry) {
        return Feature.of(geometry, new FeatureProperties());
    }

    public static Feature<? extends FeatureProperties> asSummary(VariantSummaryProperties properties) {
        return Feature.of(null, properties);
    }
}
