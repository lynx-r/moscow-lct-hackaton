package com.ultra.dit.stage2.service;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;
import org.mockito.Mockito;
import org.mockito.Spy;

import com.fasterxml.jackson.databind.JsonNode;
import com.ultra.dit.BaseTest;
import com.ultra.dit.common.constants.FeaturePropertiesStyles;
import com.ultra.dit.config.DebugProperties;
import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.stage1.ParseService;
import com.ultra.dit.stage1.SpatialIndexService;
import com.ultra.dit.stage1.TransformService;
import com.ultra.dit.stage2.config.Stage2Properties;
import com.ultra.dit.stage2.model.ClustersRoutingResult;
import com.ultra.dit.stage2_test.FeatureMapper;
import com.ultra.dit.stage2_test.ProcessStage2RoutesService;
import com.ultra.dit.util.DebugUtils;
import com.ultra.dit.util.STRtreeVisualizer;

import lombok.NonNull;

class NetworkRoutingServiceTest extends BaseTest {

  // Вычисляет общий минимальный охватывающий прямоугольник (Envelope)
  // для кластера ОКС с учетом технологического буфера
  private static final double CLUSTER_BUFFER_METERS = 500.0;

  // Размер кластера ОКС
  private static final double CLUSTER_SIZE_METERS = 10.0;

  private final double BUFFER_RADIUS_METERS = 2.0;

  private final double OKS_CONNECTION_POINT_BUFFER_METERS = 2.0;

  private static final double PATH_UNIQUE_PERCENT = 0.7;

  private static final int MAX_VARIANTS = 3;
  private static final int MAX_VALENCE = 4;

  // Запрашиваем избыточное количество путей (например, 10), чтобы выбрать 3
  private static final int CHOOSE_FROM_PATHS_COUNT = 3;

  private static final int PARALLEL_CLUSTER_COUNT = 3;

  // из скольки кратчайших путей выбирать
  private static final int CLOSES_SOURCES_COUNT = 5;

  // Шаг дискретизации (нормативный отступ от препятствий в метрах)
  private static final double OBSTACLE_OFFSET_METERS = 3.0;

  @Spy
  private ParseService parserService = new ParseService(OBJECT_MAPPER);

  private ProcessStage2RoutesService processStage2RoutesService;

  private NetworkRoutingService routingService;
  private GeometryFactory gf = new GeometryFactory();

  @BeforeEach
  void setUp() {
    var debugConfig = new DebugProperties(DebugUtils.DEFAULT_DEBUG_FOLDER, true, false, "");
    new DebugUtils(debugConfig);

    // SpatialIndexService
    Mockito.when(properties.getOksConnectionPointBufferMeters()).thenReturn(OKS_CONNECTION_POINT_BUFFER_METERS);

    // OksClusteringService
    Mockito.when(properties.getClusterSizeMeters()).thenReturn(CLUSTER_SIZE_METERS);

    // LocalBoundingBoxService
    Mockito.when(properties.getClusterBufferMeters()).thenReturn(CLUSTER_BUFFER_METERS);

    // NetworkTopologyGeneratorService
    Mockito.when(properties.getMaxVariants()).thenReturn(MAX_VARIANTS);
    Mockito.when(properties.getMaxValence()).thenReturn(MAX_VALENCE);
    Mockito.when(properties.getChooseFromPathsCount()).thenReturn(CHOOSE_FROM_PATHS_COUNT);
    Mockito.when(properties.getPathUniquePercent()).thenReturn(PATH_UNIQUE_PERCENT);
    Mockito.when(properties.getClosesSourcesCount()).thenReturn(CLOSES_SOURCES_COUNT);

    // NetworkRoutingService
    Mockito.when(properties.getParallelClusterCount()).thenReturn(PARALLEL_CLUSTER_COUNT);

    // DirectedRoadmapBuilder
    Mockito.when(properties.getConstraintOffsetMeters()).thenReturn(OBSTACLE_OFFSET_METERS);

    // properties.copy() в NetworkRoutingService::executeRoutingPipeline
    Stage2Properties copy = new Stage2Properties();
    copy.setBufferRadiusMeters(BUFFER_RADIUS_METERS);
    copy.setClusterSizeMeters(CLUSTER_SIZE_METERS);
    copy.setClusterBufferMeters(CLUSTER_BUFFER_METERS);

    copy.setMaxVariants(MAX_VARIANTS);
    copy.setMaxValence(MAX_VALENCE);
    copy.setChooseFromPathsCount(CHOOSE_FROM_PATHS_COUNT);
    copy.setPathUniquePercent(PATH_UNIQUE_PERCENT);
    copy.setClosesSourcesCount(CLOSES_SOURCES_COUNT);

    copy.setConstraintOffsetMeters(OBSTACLE_OFFSET_METERS);

    Mockito.when(properties.copy()).thenReturn(copy);

    TransformService transformService = new TransformService();
    SpatialIndexService spatialIndexService = new SpatialIndexService(properties);

    OksClusteringService clusteringService = new OksClusteringService(properties);
    // LocalBoundingBoxService bboxService = new
    // LocalBoundingBoxService(properties);
    // CollisionDetectionService collisionService = new
    // CollisionDetectionService(BUFFER_RADIUS_METERS);
    // NetworkTopologyGeneratorService topologyGenerator = new
    // NetworkTopologyGeneratorService(properties);

    routingService = new NetworkRoutingService(
        clusteringService,
        // bboxService,
        // collisionService,
        // topologyGenerator,
        properties);

    processStage2RoutesService = new ProcessStage2RoutesService(transformService, spatialIndexService,
        routingService);
  }

  @Test
  void testOrganizer() {
    var features = featuresOrganizer;
    var featureCollection = processStage2RoutesService.buildRoutesAsFeatureCollection(features.stream(), true);

    DebugUtils.writeFeatures(featureCollection.getFeatures().stream(), "result-organizer");

    assertFalse(featureCollection.getFeatures().isEmpty());
  }

  @Test
  void testBase() {
    var features = featuresBase;
    var featureCollection = processStage2RoutesService.buildRoutesAsFeatureCollection(features.stream(), true);

    DebugUtils.writeFeatures(featureCollection.getFeatures().stream(), "result-base");

    assertFalse(featureCollection.getFeatures().isEmpty());
  }

  @Test
  void testOrganizerDetails() {
    var features = featuresOrganizer;
    var clustersRoutingResult = processStage2RoutesService.buildRoutes(features.stream(), true);

    writeResults(clustersRoutingResult);

    assertFalse(clustersRoutingResult.getRoutes().isEmpty());
  }

  @Test
  void testBaseDetails() {
    var features = featuresBase;
    var clustersRoutingResult = processStage2RoutesService.buildRoutes(features.stream(), true);

    writeResults(clustersRoutingResult);

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
    // TODO: закомментровано, чтобы компилировалось
    // OksObject oks = OksObject.of(
    // Feature.of(connectionPoint,
    // new FeatureProperties("OKS-1", ObjectType.OKS_CONNECTION_POINT, null,
    // 1.5, null, "OKS-1", null, null)),
    // building);
    // var oksObjects = List.of(oks);
    var oksObjects = List.of();

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

    // IndexContainer globalIndexes = new IndexContainer(constraintsTree,
    // networksTree, nodesTree, oksObjects);

    // // 4. Запуск оркестратора
    // List<List<RoutingResult>> results =
    // routingService.executeRoutingPipeline(globalIndexes);

    // ClustersRoutingResult clustersRoutingResult = processStage2RoutesService
    // .mapResultsToClustersRoutingResult(results, false);

    // clustersRoutingResult.getRoutes().forEach(cluster -> {
    // AtomicInteger index = new AtomicInteger(0);
    // cluster.getVariants().forEach((variant) -> {
    // DebugUtils.writeFeaturesIn32637(variant.getFeatures().stream(),
    // "result-variant-" + index.incrementAndGet());
    // });
    // });

    // // 5. Проверки
    // assertFalse(results.isEmpty());
    // List<RoutingResult> clusterVariants = results.get(0);
    // assertTrue(clusterVariants.get(0).isConnected(), "Трасса должна найти
    // обходной путь");

    // // Проверяем, что ни один сгенерированный отрезок не пересекает буфер
    // // препятствия
    // for (Feature pipeline : clusterVariants.get(0).getNewPipelines()) {
    // assertFalse(pipeline.getGeometry().buffer(2.0).intersects(obstacle),
    // "Трасса задела препятствие!");
    // }
  }

  // @Test()
  // void testPrintCoords() {
  // var geometryFactory = new GeometryFactory();
  // var line = geometryFactory.createLineString(new Coordinate[] {
  // new Coordinate(414545.8493114061, 6175503.658660367),
  // new Coordinate(412429.8920690528, 6171210.8233968755),
  // });
  // Stream<Feature<FeatureProperties>> features =
  // Stream.of(Feature.withDefaultProperties(line));
  // DebugUtils.writeFeaturesIn32637(features, "000-PrintCoords");
  // }

  private void writeResults(ClustersRoutingResult clustersRoutingResult) {
    AtomicInteger clusterIndex = new AtomicInteger(0);
    clustersRoutingResult.getRoutes().forEach(cluster -> {
      int clusterIdx = clusterIndex.getAndIncrement();
      AtomicInteger variantIndex = new AtomicInteger(0);
      cluster.getVariants().forEach((variant) -> {
        if (variant.getFeatures().isEmpty()) {
          DebugUtils.writeFeatures(Stream.of(),
              clusterIdx + "-cluster-0-variant-result-blank");
          return;
        }
        var variantIdx = variantIndex.getAndIncrement();
        // variant.getFeatures().addAll(features);
        @NonNull
        Stream<Feature<? extends FeatureProperties>> features = variant.getFeatures().stream()
            .map(f -> {
              Map<String, JsonNode> styleProperties = new HashMap<>();
              FeatureProperties properties = f.getProperties();
              if (properties.isPointGeometry()) {
                styleProperties = FeaturePropertiesStyles.RESULT_POINT;
              } else if (properties.isComplexGeometry()) {
                styleProperties = FeaturePropertiesStyles.RESULT_STROKE;
              }
              properties.addAdditionalProperties(styleProperties);
              return Feature.of(f.getGeometry(), properties);
            });
        DebugUtils.writeFeatures(features,
            clusterIdx + "-cluster-" + variantIdx + "-variant-result");
      });
    });
  }

}
