package com.ultra.dit.dto.geojson;

import java.util.List;

import com.ultra.dit.dto.geojson.enums.GeoJsonType;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Data
@NoArgsConstructor
@RequiredArgsConstructor(staticName = "of")
public class FeatureCollection {
    @NonNull
    private List<Feature<? extends FeatureProperties>> features;

    private final GeoJsonType type = GeoJsonType.FEATURE_COLLECTION;
}
