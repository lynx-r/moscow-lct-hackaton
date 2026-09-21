package com.ultra.dit.stage2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.mockito.Spy;

import com.ultra.dit.BaseTest;
import com.ultra.dit.dto.IndexContainer;
import com.ultra.dit.stage1.ParseService;
import com.ultra.dit.stage1.TransformService;
import com.ultra.dit.stage2.graph.NetworkTopologyGenerator;
import com.ultra.dit.stage2.model.OksObject;
import com.ultra.dit.stage2.model.RoutingResult;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class NetworkRoutingServiceTest extends BaseTest {

  private static final double BUFFER_METERS = 500.0;

  @Spy
  private ParseService parserService = new ParseService(OBJECT_MAPPER);

  private NetworkRoutingService routingService;
  private GeometryFactory gf = new GeometryFactory();

  // Реальные сервисы для сквозного тестирования цепочки трансформации
  private ParseService parseService;
  private TransformService transformService;

  @BeforeEach
  void setUp() {
    // Инициализируем реальные легковесные сервисы без Spring-контекста для скорости
    transformService = new TransformService();

    OksClusteringService clusteringService = new OksClusteringService();
    LocalBoundingBoxService bboxService = new LocalBoundingBoxService(BUFFER_METERS);
    CollisionDetectionService collisionService = new CollisionDetectionService();
    NetworkTopologyGenerator topologyGenerator = new NetworkTopologyGenerator();

    routingService = new NetworkRoutingService(
        clusteringService, bboxService, collisionService, topologyGenerator);
  }

  @Test
  void testSuccessfulRoutingAroundObstacle() {
    // 1. Создаем тестовое препятствие (здание-полигон) прямо на пути трассы
    Coordinate[] polyCoords = new Coordinate[] {
        new Coordinate(10, 5), new Coordinate(20, 5),
        new Coordinate(20, 15), new Coordinate(10, 15),
        new Coordinate(10, 5)
    };
    Polygon obstacle = gf.createPolygon(polyCoords);
    STRtree constraintsTree = new STRtree();
    constraintsTree.insert(obstacle.getEnvelopeInternal(), obstacle);

    // 2. Создаем существующую сеть (точка старта)
    LineString existingNet = gf.createLineString(new Coordinate[] {
        new Coordinate(0, 10), new Coordinate(5, 10)
    });
    STRtree networksTree = new STRtree();
    networksTree.insert(existingNet.getEnvelopeInternal(), existingNet);

    STRtree nodesTree = new STRtree(); // пустой для теста
    IndexContainer globalIndexes = new IndexContainer(constraintsTree, networksTree, nodesTree);

    // 3. Создаем целевой ОКС (точка финиша за препятствием)
    Point connectionPoint = gf.createPoint(new Coordinate(25, 10));
    Polygon building = gf.createPolygon(new Coordinate[] {
        new Coordinate(24, 9), new Coordinate(26, 9),
        new Coordinate(26, 11), new Coordinate(24, 11),
        new Coordinate(24, 9)
    });
    OksObject oks = new OksObject("OKS-1", connectionPoint, building, 1.5);

    // 4. Запуск оркестратора
    List<List<RoutingResult>> results = routingService.executeRoutingPipeline(List.of(oks), globalIndexes);

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
