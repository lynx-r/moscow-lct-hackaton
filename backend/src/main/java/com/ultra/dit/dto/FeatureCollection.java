package com.ultra.dit.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

public class FeatureCollection {

    @Setter
    @Getter
    public List<Feature> features;

    public FeatureCollection() {
    }

    public FeatureCollection(List<Feature> features) {
        this.features = features;
    }
}
