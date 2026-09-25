package com.ultra.dit.stage3.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;

import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.FeatureProperties;
import com.ultra.dit.dto.ObjectType;
import com.ultra.dit.stage2.model.IndexContainer;
import com.ultra.dit.stage2.model.RoutingResult;
import com.ultra.dit.stage3.model.ConnectionResult;
import com.ultra.dit.stage3.model.NetworkNode;

class ConnectionSelectionServiceTest {
    private final GeometryFactory gf = new GeometryFactory();
    private final ConnectionSelectionService service = new ConnectionSelectionService();

    private LineString line(double... xy) {
        Coordinate[] c = new Coordinate[xy.length / 2];
        for (int i = 0; i < c.length; i++)
            c[i] = new Coordinate(xy[2 * i], xy[2 * i + 1]);
        return gf.createLineString(c);
    }

    private IndexContainer indices(List<LineString> lines, Point... points) {
        STRtree networks = new STRtree();
        STRtree nodes = new STRtree();
        int id = 0;
        for (LineString l : lines) {
            Feature f = new Feature();
            FeatureProperties p = new FeatureProperties();
            p.setId("pipe-" + id++);
            p.setObjectType(ObjectType.HEAT_NETWORK);
            p.setDiameter(100);
            f.setProperties(p);
            f.setGeometry(l);
            networks.insert(l.getEnvelopeInternal(), f);
        }
        for (Point point : points) {
            nodes.insert(point.getEnvelopeInternal(), new NetworkNode("chamber-" + id++, "heat_chamber", point, false));
        }
        return new IndexContainer(new STRtree(), networks, nodes, Collections.emptyList());
    }

    @Test
    void splitsAndPreservesLengthAndParent() {
        IndexContainer input = indices(List.of(line(0, 0, 50, 0, 100, 0)));
        var session = service.newSession(input);
        var result = session.connect("oks", line(40, 20, 40, 1), r -> true);
        assertEquals(ConnectionResult.ConnectionType.PIPE_TAP, result.getConnectionType());
        assertEquals(2, result.getCreatedSegments().size());
        assertEquals(100, session.getSegments().stream().mapToDouble(s -> s.getGeometry().getLength()).sum(), 1e-9);
        assertTrue(result.getCreatedSegments().stream()
                .allMatch(s -> s.getParentId().equals("pipe-0") && s.getDiameter() == 100));
        assertEquals(new Coordinate(40, 0), result.getRoute().getEndPoint().getCoordinate());
        assertEquals(6, session.buildGraph().edgeSet().size());
        assertEquals(1, input.getNetworksTree().size());
        assertEquals(1, service.newSession(input).getSegments().size());
    }

    @Test
    void reusesChamberAndCountsNewConnections() {
        var session = service.newSession(indices(List.of(line(0, 0, 100, 0)), gf.createPoint(new Coordinate(0, 0))));
        for (int i = 0; i < 3; i++) {
            var result = session.connect("oks" + i, line(-20, 20 + i, 0, 1), r -> true);
            assertEquals(ConnectionResult.ConnectionType.EXISTING_CHAMBER, result.getConnectionType());
        }
        assertThrows(IllegalStateException.class, () -> session.connect("fourth", line(-20, 30, 0, 1), r -> true));
    }

    @Test
    void radiusIsCircularAndInclusive() {
        var session = service.newSession(indices(List.of(line(0, 0, 100, 0)), gf.createPoint(new Coordinate(0, 0))));
        assertEquals(ConnectionResult.ConnectionType.EXISTING_CHAMBER,
                session.connect("oks", line(0, 20, 0, 10), r -> true).getConnectionType());
        var other = service.newSession(indices(List.of(line(0, 0, 100, 0)), gf.createPoint(new Coordinate(0, 0))));
        assertEquals(ConnectionResult.ConnectionType.PIPE_TAP,
                other.connect("oks", line(9, 20, 9, 9), r -> true).getConnectionType());
    }

    @Test
    void endpointDoesNotProduceZeroLengthSegment() {
        var session = service.newSession(indices(List.of(line(0, 0, 100, 0))));
        var result = session.connect("oks", line(-10, 20, -1, 1), r -> true);
        assertNull(result.getReplacedSegment());
        assertTrue(result.getCreatedSegments().isEmpty());
        assertEquals(1, session.getSegments().size());
        assertEquals(4, session.buildGraph().edgeSet().size());
    }

    @Test
    void failedValidationDoesNotMutateNetwork() {
        var session = service.newSession(indices(List.of(line(0, 0, 100, 0))));
        assertThrows(IllegalStateException.class, () -> session.connect("oks", line(40, 20, 40, 1), r -> false));
        assertEquals(1, session.getSegments().size());
        assertEquals(2, session.buildGraph().edgeSet().size());
    }

    @Test
    void emptyNetworkFailsClearly() {
        var session = service.newSession(indices(Collections.emptyList()));
        assertThrows(IllegalStateException.class, () -> session.connect("oks", line(0, 0, 10, 0), r -> true));
    }

    @Test
    void convertsStage2Direction() {
        var route = service
                .routeFromStage2(new RoutingResult(List.of(line(0, 0, 10, 0), line(10, 0, 10, 20)), List.of(), true));
        assertEquals(new Coordinate(10, 20), route.getStartPoint().getCoordinate());
        assertEquals(new Coordinate(0, 0), route.getEndPoint().getCoordinate());
        assertEquals(3, route.getNumPoints());
    }
}
