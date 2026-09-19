package com.ultra.dit.dto;

import lombok.Getter;
import lombok.Setter;
import org.locationtech.jts.geom.Geometry;

import java.util.Map;

public class Feature {

    @Setter
    @Getter
    private Geometry geometry;

    @Setter
    @Getter
    private Map<String, Object> properties;

    public Feature() {
    }

    public Feature(Geometry geometry, Map<String, Object> properties) {
        this.geometry = geometry;
        this.properties = properties;
    }
}
