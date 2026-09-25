package com.ultra.dit.stage2.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;
import org.mockito.Spy;

import com.ultra.dit.helpers.STRtreeVisualizer;
import com.ultra.dit.service.DebugUtils;
import com.ultra.dit.stage1.ParseService;
import com.ultra.dit.stage1.SpatialIndexService;
import com.ultra.dit.stage1.TransformService;
import com.ultra.dit.stage2.model.ClustersRoutingResult;
import com.ultra.dit.stage2.model.IndexContainer;
import com.ultra.dit.stage2.model.OksObject;
import com.ultra.dit.stage2.model.RoutingResult;
import com.ultra.dit.stage2_test.FeatureMapper;
import com.ultra.dit.stage2_test.ProcessStage2RoutesService;

class NetworkRoutingServiceTest extends BaseStage2Test {

        private static final double BUFFER_METERS = 500.0;
        private static final double BUFFER_RADIUS_METERS = 0.5;
        private static final double CLUSTER_SIZE_METERS = 100.0;
        private static final int MAX_VARIANTS = 3;
        private static final int MAX_VALENCE = 4; // Максимальная степень узла для камер по ТЗ
        private static final int CHOOSE_FROM_PATHS_COUNT = 4; // Максимальная степень узла для камер по ТЗ

        @Spy
        private ParseService parserService = new ParseService(OBJECT_MAPPER);

        private ProcessStage2RoutesService processStage2RoutesService;

        private NetworkRoutingService routingService;
        private GeometryFactory gf = new GeometryFactory();

        @BeforeEach
        void setUp() {
                TransformService transformService = new TransformService();
                SpatialIndexService spatialIndexService = new SpatialIndexService(OKS_CONNECTION_POINT_BUFFER_METERS);

                OksClusteringService clusteringService = new OksClusteringService(CLUSTER_SIZE_METERS);
                LocalBoundingBoxService bboxService = new LocalBoundingBoxService(BUFFER_METERS);
                // CollisionDetectionService collisionService = new
                // CollisionDetectionService(BUFFER_RADIUS_METERS);
                NetworkTopologyGeneratorService topologyGenerator = new NetworkTopologyGeneratorService(MAX_VARIANTS,
                                MAX_VALENCE, CHOOSE_FROM_PATHS_COUNT);

                routingService = new NetworkRoutingService(
                                clusteringService, bboxService,
                                // collisionService,
                                topologyGenerator);

                processStage2RoutesService = new ProcessStage2RoutesService(transformService, spatialIndexService,
                                routingService);
        }

        @Test
        void test1() {
                var features = testFeatureCollection.getFeatures();
                var clustersRoutingResult = processStage2RoutesService.buildRoutes(features.stream(), true);

                AtomicInteger clusterIndex = new AtomicInteger(0);
                clustersRoutingResult.getRoutes().forEach(cluster -> {
                        int clusterIdx = clusterIndex.incrementAndGet();
                        AtomicInteger variantIndex = new AtomicInteger(0);
                        cluster.getVariants().forEach((variant) -> {
                                var variantIdx = variantIndex.incrementAndGet();
                                variant.getFeatures().addAll(features);
                                DebugUtils.writeFeatures(variant.getFeatures().stream(),
                                                "result-cluster-" + clusterIdx + "-variant-" + variantIdx);
                        });
                });

                assertFalse(clustersRoutingResult.getRoutes().isEmpty());
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

                // Также можно зарегистрировать конечную точку существующей сети как
                // точку врезки
                Point networkNode = gf.createPoint(new Coordinate(13, 7));
                // 2. Создаем существующую сеть (точка старта)
                LineString existingNet = gf.createLineString(new Coordinate[] {
                                new Coordinate(12, 10), networkNode.getCoordinate()
                });
                STRtree networksTree = new STRtree();
                networksTree.insert(existingNet.getEnvelopeInternal(), existingNet);

                // 3. Создаем целевой ОКС (точка финиша за препятствием)
                Point connectionPoint = gf.createPoint(new Coordinate(24, 7));
                Polygon building = gf.createPolygon(new Coordinate[] {
                                new Coordinate(23, 6), new Coordinate(25, 6),
                                new Coordinate(25, 8), new Coordinate(23, 8),
                                new Coordinate(23, 6)
                });
                // Добавляем полигон здания ОКС, чтобы к нему можно было протянуть трубу
                constraintsTree.insert(building.getEnvelopeInternal(), building);
                OksObject oks = new OksObject("OKS-1", connectionPoint, building, 1.5);
                var oksObjects = List.of(oks);

                // TODO: debug start
                @SuppressWarnings("null")
                // polyCoords
                var testGeometries = List.of(obstacle, networkNode, existingNet, connectionPoint, building).stream();
                // .stream() .flatMap(List::stream);
                var testMapGeometriesToFeatures = FeatureMapper.mapStreamGeometriesToFeatures(testGeometries);
                DebugUtils.writeFeaturesIn32637(testMapGeometriesToFeatures, "testFeatureCollection");
                // TODO: debug stop

                STRtree nodesTree = new STRtree();
                // Обязательно регистрируем точку подключения ОКС в индексе узлов карты
                nodesTree.insert(connectionPoint.getEnvelopeInternal(), connectionPoint);
                nodesTree.insert(networkNode.getEnvelopeInternal(), networkNode);

                // TODO: debug start
                var constraints = STRtreeVisualizer.extractTreeBoundaries(constraintsTree);
                var networks = STRtreeVisualizer.extractTreeBoundaries(networksTree);
                var nodes = STRtreeVisualizer.extractTreeBoundaries(nodesTree);

                @SuppressWarnings("null")
                var treeGeometries = List.of(constraints, networks, nodes, List.of(building)).stream()
                                .flatMap(List::stream);
                var mapGeometriesToFeatures = FeatureMapper.mapStreamGeometriesToFeatures(treeGeometries);
                DebugUtils.writeFeaturesIn32637(mapGeometriesToFeatures,
                                "treeFeatureCollection");
                // TODO: debug stop

                IndexContainer globalIndexes = new IndexContainer(constraintsTree, networksTree, nodesTree, oksObjects);

                // 4. Запуск оркестратора
                List<List<RoutingResult>> results = routingService.executeRoutingPipeline(globalIndexes);

                ClustersRoutingResult clustersRoutingResult = processStage2RoutesService
                                .mapResultsToClustersRoutingResult(results, false);

                clustersRoutingResult.getRoutes().forEach(cluster -> {
                        AtomicInteger index = new AtomicInteger(0);
                        cluster.getVariants().forEach((variant) -> {
                                DebugUtils.writeFeaturesIn32637(variant.getFeatures().stream(),
                                                "result-variant-" + index.incrementAndGet());
                        });
                });

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
