package com.ultra.dit.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeatureCollection {
    private List<Feature> features;
    private GeoJsonType type;
}
