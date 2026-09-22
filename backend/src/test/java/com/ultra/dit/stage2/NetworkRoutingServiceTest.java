package com.ultra.dit.stage2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.index.strtree.STRtree;
import org.mockito.Spy;

import com.ultra.dit.BaseTest;
import com.ultra.dit.dto.FeatureCollection;
import com.ultra.dit.helpers.STRtreeVisualizer;
import com.ultra.dit.service.DebugUtils;
import com.ultra.dit.stage1.ParseService;
import com.ultra.dit.stage1.TransformService;
import com.ultra.dit.stage2.graph.NetworkTopologyGenerator;
import com.ultra.dit.stage2.model.IndexContainer;
import com.ultra.dit.stage2.model.OksObject;
import com.ultra.dit.stage2.model.RoutingResult;
import com.ultra.dit.stage2_test.FeatureMapper;

class NetworkRoutingServiceTest extends BaseTest {

        private static final double BUFFER_METERS = 9.0;
        private static final double BUFFER_RADIUS_METERS = 2.0;

        @Spy
        private ParseService parserService = new ParseService(OBJECT_MAPPER);

        private NetworkRoutingService routingService;
        private GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 32637);

        // Реальные сервисы для сквозного тестирования цепочки трансформации
        private ParseService parseService;
        private TransformService transformService;

        @BeforeEach
        void setUp() {
                // Инициализируем реальные легковесные сервисы без Spring-контекста для скорости
                transformService = new TransformService();

                OksClusteringService clusteringService = new OksClusteringService();
                LocalBoundingBoxService bboxService = new LocalBoundingBoxService(BUFFER_METERS);
                CollisionDetectionService collisionService = new CollisionDetectionService(BUFFER_RADIUS_METERS);
                NetworkTopologyGenerator topologyGenerator = new NetworkTopologyGenerator();

                routingService = new NetworkRoutingService(
                                clusteringService, bboxService, collisionService, topologyGenerator);
        }

        @Test
        void testSuccessfulRoutingAroundObstacle() {
                // 1. Создаем тестовое препятствие (здание-полигон) прямо на пути трассы
                Coordinate[] polyCoords = new Coordinate[] {
                                new Coordinate(17, 5), new Coordinate(20, 5),
                                new Coordinate(20, 15), new Coordinate(17, 15),
                                new Coordinate(17, 5)
                };
                Polygon obstacle = gf.createPolygon(polyCoords);
                STRtree constraintsTree = new STRtree();
                constraintsTree.insert(obstacle.getEnvelopeInternal(), obstacle);

                // Также можно зарегистрировать конечную точку существующей сети (15, 10) как
                // точку врезки
                Point networkNode = gf.createPoint(new Coordinate(15, 10));
                // 2. Создаем существующую сеть (точка старта)
                LineString existingNet = gf.createLineString(new Coordinate[] {
                                new Coordinate(0, 10), networkNode.getCoordinate()
                });
                STRtree networksTree = new STRtree();
                networksTree.insert(existingNet.getEnvelopeInternal(), existingNet);

                // 3. Создаем целевой ОКС (точка финиша за препятствием)
                Point connectionPoint = gf.createPoint(new Coordinate(25, 10));
                Polygon building = gf.createPolygon(new Coordinate[] {
                                new Coordinate(24, 9), new Coordinate(26, 9),
                                new Coordinate(26, 11), new Coordinate(24, 11),
                                new Coordinate(24, 9)
                });
                OksObject oks = new OksObject("OKS-1", connectionPoint, building, 1.5);
                var oksObjects = List.of(oks);

                STRtree nodesTree = new STRtree();
                // Обязательно регистрируем точку подключения ОКС в индексе узлов карты
                nodesTree.insert(connectionPoint.getEnvelopeInternal(), connectionPoint);
                nodesTree.insert(networkNode.getEnvelopeInternal(), networkNode);

                // TODO: debug start
                var constraints = STRtreeVisualizer.extractTreeBoundaries(constraintsTree);
                var networks = STRtreeVisualizer.extractTreeBoundaries(networksTree);
                var nodes = STRtreeVisualizer.extractTreeBoundaries(nodesTree);

                @SuppressWarnings("null")
                var treeGeometries = List.of(constraints, networks, nodes).stream().flatMap(List::stream);
                var mapGeometriesToFeatures = FeatureMapper.mapStreamGeometriesToFeatures(treeGeometries)
                                .collect(Collectors.toList());
                var treeFeatureCollection = FeatureCollection.of(mapGeometriesToFeatures);
                DebugUtils.writeFeatureCollectionToFile(treeFeatureCollection,
                                Paths.get("data", "debug", "geojson", "treeFeatureCollection.geojson"));
                // TODO: debug stop

                IndexContainer globalIndexes = new IndexContainer(constraintsTree, networksTree, nodesTree, oksObjects);

                // 4. Запуск оркестратора
                List<List<RoutingResult>> results = routingService.executeRoutingPipeline(globalIndexes);

                // 5. Проверки
                assertFalse(results.isEmpty());
                List<RoutingResult> clusterVariants = results.get(0);
                assertTrue(clusterVariants.get(0).isConnected(), "Трасса должна найти обходной путь");

                // Проверяем, что ни один сгенерированный отрезок не пересекает буфер
                // препятствия
                for (LineString pipeline : clusterVariants.get(0).getNewPipelines()) {
                        assertFalse(pipeline.buffer(2.0).intersects(obstacle), "Трасса задела препятствие!");
                }
        }
}
