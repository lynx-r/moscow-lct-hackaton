package com.ultra.dit.stage3;

import lombok.Getter;



import org.locationtech.jts.geom.Point;

public class NetworkNode {

    public static final String HEAT_CHAMBER = "heat_chamber";

    @Getter
    private final String id;
    @Getter
    private final String objectType;
    @Getter
    private final Point geometry;
    @Getter
    private final boolean virtual;

    public NetworkNode(String id, String objectType, Point geometry, boolean virtual){
        this.id = id;
        this.objectType = objectType;
        this.geometry = geometry;
        this.virtual = virtual;
    }

    public boolean isHeatChamber() {
        return HEAT_CHAMBER.equals(objectType);
    }

}
