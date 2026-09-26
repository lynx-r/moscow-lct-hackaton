package com.ultra.dit.stage3.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Service;

import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.enums.ObjectType;
import com.ultra.dit.stage2.model.IndexContainer;
import com.ultra.dit.stage2.model.RoutingResult;
import com.ultra.dit.stage3.model.ConnectionResult;
import com.ultra.dit.stage3.model.NetworkNode;
import com.ultra.dit.stage3.model.NetworkSegment;

/**
 * Выбор точки присоединения в общей планарной системе координат с единицей
 * метр.
 */
@Service
public class ConnectionSelectionService {
    /**
     * Отдельная сессия для каждого альтернативного варианта; исходные индексы не
     * меняются.
     * 
     * @param indexes
     * @return Session
     */
    public Session newSession(IndexContainer indexes) {
        return new Session(indexes);
    }

    /**
     * Этап 2 выдает ребра в порядке сеть -> ОКС; этап 3 принимает ОКС -> сеть.
     * 
     * @param result
     * @return
     */
    public LineString routeFromStage2(RoutingResult result) {
        // TODO: uncomment
        // if (!result.isConnected() || result.getNewPipelines().isEmpty()) {
        // throw new IllegalArgumentException("Маршрут этапа 2 не соединен.");
        // }
        // List<Coordinate> coordinates = new ArrayList<>();
        // for (LineString edge : result.getNewPipelines()) {
        // if (edge.isEmpty())
        // throw new IllegalArgumentException("Пустое ребро маршрута");
        // if (!coordinates.isEmpty() && !coordinates.get(coordinates.size() - 1)
        // .equals2D(edge.getCoordinateN(0))) {
        // throw new IllegalArgumentException("Ребра этапа 2 не образуют непрерывный
        // упорядоченный путь");
        // }
        // for (Coordinate c : edge.getCoordinates()) {
        // if (coordinates.isEmpty() || !coordinates.get(coordinates.size() -
        // 1).equals2D(c)) {
        // coordinates.add(c.copy());
        // }
        // }
        // }
        // Collections.reverse(coordinates);
        // return result.getNewPipelines().get(0).getFactory()
        // .createLineString(coordinates.toArray(new Coordinate[0]));
        GeometryFactory gf = new GeometryFactory();
        return gf.createLineString();
    }

    /**
     * Изменяемое состояние одного варианта.
     * Не предназначено для совместного доступа потоков.
     */
    public static final class Session {
        private final List<NetworkSegment> segments = new ArrayList<>();
        private final List<NetworkNode> nodes = new ArrayList<>();
        private final List<LineString> connections = new ArrayList<>();
        private STRtree networksTree;
        private STRtree nodesTree;

        private Session(IndexContainer indices) {
            Envelope globalSearchEnv = new Envelope(-Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
                    Double.MAX_VALUE);
            var networksQuery = indices.getNetworksTree().query(globalSearchEnv);
            for (Object item : networksQuery) {
                if (item instanceof NetworkSegment) {
                    NetworkSegment s = (NetworkSegment) item;
                    addSegment(s.getId(), s.getParentId(), s.getDiameter(), s.getGeometry());
                } else if (item instanceof Feature) {
                    Feature f = (Feature) item;
                    if (f.getProperties().getObjectType() != ObjectType.HEAT_NETWORK)
                        continue;
                    Geometry g = f.getGeometry();
                    if (!(g instanceof LineString) && !(g instanceof MultiLineString)) {
                        throw new IllegalArgumentException("Сеть должна быть линейной");
                    }
                    // for (int i = 0; i < g.getNumGeometries(); i++) {
                    // String id = f.getProperties().getId();
                    // String idGeometry = g instanceof LineString ? id : id + ":" + i;
                    // int diameter = f.getProperties().getDiameter() == null ? 0 :
                    // f.getProperties().getDiameter();
                    // LineString geometryN = (LineString) g.getGeometryN(i);
                    // addSegment(idGeometry, id, diameter, geometryN);
                    // }
                } else {
                    throw new IllegalArgumentException("Неподдерживаемый элемент индекса сети");
                }
            }
            var nodesQuery = indices.getNodesTree().query(globalSearchEnv);
            for (Object item : nodesQuery) {
                if (item instanceof NetworkNode) {
                    NetworkNode n = (NetworkNode) item;
                    NetworkNode node = new NetworkNode(n.getId(), n.getObjectType(),
                            (Point) n.getGeometry().copy(), n.isVirtual());
                    nodes.add(node);
                } else if (item instanceof Feature) {
                    Feature f = (Feature) item;
                    boolean isHeatChamber = f.getProperties().getObjectType() == ObjectType.HEAT_CHAMBER;
                    if (isHeatChamber) {
                        NetworkNode chamberNode = new NetworkNode(f.getProperties().getId(), NetworkNode.HEAT_CHAMBER,
                                (Point) f.getGeometry().copy(), false);
                        nodes.add(chamberNode);
                    }
                } else {
                    throw new IllegalArgumentException("Неподдерживаемый элемент индекса узла");
                }
            }
            rebuildIndices();
        }

        /**
         * Добавляем сегмент
         * 
         * @param id
         * @param parentId
         * @param diameter
         * @param line
         */
        private void addSegment(String id, String parentId, double diameter, LineString line) {
            validateLine(line);
            segments.add(new NetworkSegment(id, parentId, diameter, (LineString) line.copy()));
        }

        /**
         * validRoute повторно проверяет коллизии после перемещения конца трассы.
         * При отказе/исключении состояние сессии не изменяется.
         */
        public ConnectionResult connect(String oksId, LineString route, Predicate<LineString> validRoute) {
            Objects.requireNonNull(validRoute, "Требуется валидатор маршрута.");
            validateLine(route);
            Point end = route.getEndPoint();
            Envelope search = new Envelope(end.getEnvelopeInternal());
            search.expandBy(10.0);
            List<NetworkNode> candidates = new ArrayList<>();
            for (Object item : nodesTree.query(search)) {
                NetworkNode n = (NetworkNode) item;
                if (n.isHeatChamber() && n.getGeometry().distance(end) <= 10.0) {
                    candidates.add(n);
                }
            }
            candidates.sort(Comparator.comparingDouble((NetworkNode n) -> n.getGeometry().distance(end))
                    .thenComparing(NetworkNode::getId, Comparator.nullsFirst(String::compareTo)));
            for (NetworkNode n : candidates) {
                if (incidentCount(n.getGeometry()) > 3)
                    continue;
                LineString adjusted = moveEnd(route, n.getGeometry().getCoordinate());
                if (adjusted.getLength() == 0 || !adjusted.isSimple() || adjusted.isClosed()
                        || !validRoute.test(adjusted))
                    continue;
                connections.add(adjusted);
                return new ConnectionResult(oksId, ConnectionResult.ConnectionType.EXISTING_CHAMBER,
                        n, adjusted, null, Collections.emptyList());
            }
            if (segments.isEmpty())
                throw new IllegalStateException("Нет доступных сетей");
            NetworkSegment nearest = (NetworkSegment) networksTree.nearestNeighbour(
                    end.getEnvelopeInternal(), end, (a, b) -> geometry(a.getItem()).distance(geometry(b.getItem())));
            LengthIndexedLine indexed = new LengthIndexedLine(nearest.getGeometry());
            double position = indexed.project(end.getCoordinate());
            Coordinate tap = indexed.extractPoint(position);
            Point chamberPoint = nearest.getGeometry().getFactory().createPoint(tap);
            // Не обходить лимит камеры созданием второй камеры в той же координате.
            int incident = incidentCount(chamberPoint);
            boolean interior = position > 0 && position < indexed.getEndIndex();
            if (incident + (interior ? 1 : 0) + 1 > 4) {
                throw new IllegalStateException("Ближайший отвод трубы превышает валентность камеры 4");
            }
            for (Object item : nodesTree.query(chamberPoint.getEnvelopeInternal())) {
                NetworkNode n = (NetworkNode) item;
                if (n.isHeatChamber() && n.getGeometry().equalsExact(chamberPoint)) {
                    throw new IllegalStateException("Существующая камера в месте врезки была забракована");
                }
            }
            LineString adjusted = moveEnd(route, tap);
            if (adjusted.getLength() == 0 || !adjusted.isSimple() || adjusted.isClosed()
                    || !validRoute.test(adjusted)) {
                throw new IllegalStateException("Измененный маршрут непроходим. Ну пути преграда");
            }
            String id = "virtual-" + UUID.randomUUID();
            NetworkNode chamber = new NetworkNode(id, NetworkNode.HEAT_CHAMBER, chamberPoint, true);
            List<NetworkSegment> created = new ArrayList<>();
            if (interior) {
                created.add(new NetworkSegment(id + ":1", nearest.getParentId(), nearest.getDiameter(),
                        (LineString) indexed.extractLine(0, position)));
                created.add(new NetworkSegment(id + ":2", nearest.getParentId(), nearest.getDiameter(),
                        (LineString) indexed.extractLine(position, indexed.getEndIndex())));
                segments.remove(nearest);
                segments.addAll(created);
            }
            nodes.add(chamber);
            connections.add(adjusted);
            rebuildIndices();
            return new ConnectionResult(oksId, ConnectionResult.ConnectionType.PIPE_TAP, chamber, adjusted,
                    interior ? nearest : null, Collections.unmodifiableList(created));
        }

        private int incidentCount(Point point) {
            int count = 0;
            for (Object item : networksTree.query(point.getEnvelopeInternal())) {
                if (((NetworkSegment) item).getGeometry().intersects(point))
                    count++;
            }
            for (LineString line : connections)
                if (line.intersects(point))
                    count++;
            return count;
        }

        private static Geometry geometry(Object item) {
            return item instanceof NetworkSegment ? ((NetworkSegment) item).getGeometry() : (Geometry) item;
        }

        private void rebuildIndices() {
            networksTree = new STRtree();
            nodesTree = new STRtree();
            for (NetworkSegment s : segments)
                networksTree.insert(s.getGeometry().getEnvelopeInternal(), s);
            for (NetworkNode n : nodes)
                nodesTree.insert(n.getGeometry().getEnvelopeInternal(), n);
            networksTree.build();
            nodesTree.build();
        }

        public List<NetworkSegment> getSegments() {
            return Collections.unmodifiableList(segments);
        }

        /**
         * Свежий граф топологии; два направления на участок, вес равен длине в метрах
         * 
         * @return
         */
        public DirectedWeightedMultigraph<Coordinate, DefaultWeightedEdge> buildGraph() {
            DirectedWeightedMultigraph<Coordinate, DefaultWeightedEdge> graph = new DirectedWeightedMultigraph<>(
                    DefaultWeightedEdge.class);
            List<LineString> lines = new ArrayList<>(connections);
            for (NetworkSegment s : segments)
                lines.add(s.getGeometry());
            for (LineString line : lines) {
                LengthIndexedLine indexed = new LengthIndexedLine(line);
                SortedSet<Double> cuts = new TreeSet<>(Arrays.asList(0.0, indexed.getEndIndex()));
                for (Object item : nodesTree.query(line.getEnvelopeInternal())) {
                    Point p = ((NetworkNode) item).getGeometry();
                    if (line.intersects(p))
                        cuts.add(indexed.project(p.getCoordinate()));
                }
                Double previous = null;
                for (double cut : cuts) {
                    if (previous != null) {
                        Coordinate from = indexed.extractPoint(previous);
                        Coordinate to = indexed.extractPoint(cut);
                        graph.addVertex(from);
                        graph.addVertex(to);
                        if (from.equals2D(to)) {
                            throw new IllegalStateException("Изолированные сегменты сети не поддерживаются.");
                        }
                        graph.setEdgeWeight(graph.addEdge(from, to), cut - previous);
                        graph.setEdgeWeight(graph.addEdge(to, from), cut - previous);
                    }
                    previous = cut;
                }
            }
            return graph;
        }
    }

    private static void validateLine(LineString line) {
        if (line == null || line.isEmpty() || line.getLength() <= 0 || !line.isSimple() || line.isClosed()) {
            throw new IllegalArgumentException("Ожидалась непустая открытая линия.");
        }
        if (line.getSRID() == 4326)
            throw new IllegalArgumentException("Координаты должны быть в метрической системе");
        for (Coordinate c : line.getCoordinates()) {
            if (!Double.isFinite(c.x) || !Double.isFinite(c.y)) {
                throw new IllegalArgumentException("Координаты не являются конечными");
            }
        }
    }

    private static LineString moveEnd(LineString route, Coordinate destination) {
        Coordinate[] coordinates = route.getCoordinates();
        coordinates[coordinates.length - 1] = destination.copy();
        LineString adjusted = route.getFactory().createLineString(coordinates);
        adjusted.setSRID(route.getSRID());
        return adjusted;
    }
}
