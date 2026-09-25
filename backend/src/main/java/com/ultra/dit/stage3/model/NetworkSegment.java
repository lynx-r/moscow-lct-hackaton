package com.ultra.dit.stage3.model;

import org.locationtech.jts.geom.LineString;

import lombok.Value;

/**
 * Сегмент существуещей тепловой сети
 */
@Value
public class NetworkSegment {
    /**
     * Уникальный ID конкретного сегмента
     */
    String id;
    /**
     * ID исходной трубы
     *
     * Если труба разрезана:
     * 125
     * превращается в:
     * 125_split_1
     * 125_split_2
     * но parendId у обоих останется 125
     */
    String parentId;
    double diameter;
    LineString geometry;

    public NetworkSegment(String id, String parentId, double diameter, LineString geometry) {
        this.id = id;
        this.parentId = parentId;
        this.diameter = diameter;
        this.geometry = geometry;
    }

    public NetworkSegment(String id, double diameter, LineString geometry) {
        this(id, id, diameter, geometry);
    }

}
