package com.ultra.dit.stage3.model;

import java.util.Collections;
import java.util.List;

import org.locationtech.jts.geom.LineString;

import lombok.Data;

@Data
public class ConnectionResult {
    public enum ConnectionType {
        EXISTING_CHAMBER,
        PIPE_TAP
    }

    private final String oksId;
    private final ConnectionType connectionType;
    private final NetworkNode chamber;
    private final LineString route;

    private final NetworkSegment replacedSegment;
    private final List<NetworkSegment> createdSegments;

    public ConnectionResult(
            String oksId,
            ConnectionType connectionType,
            NetworkNode chamber,
            LineString route,
            NetworkSegment replacedSegment,
            List<NetworkSegment> createdSegments) {
        this.oksId = oksId;
        this.connectionType = connectionType;
        this.chamber = chamber;
        this.route = route;
        this.replacedSegment = replacedSegment;
        this.createdSegments = createdSegments == null
                ? Collections.emptyList()
                : createdSegments;
    }

}
