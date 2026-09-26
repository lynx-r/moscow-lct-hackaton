package com.ultra.dit.stage2.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder;

import com.ultra.dit.dto.geojson.enums.ObjectType;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.stage2.model.LocalIndexContainer;
import com.ultra.dit.stage2.model.NodeType;
import com.ultra.dit.stage2.model.OksObject;
import com.ultra.dit.stage2.model.VirtualNode;
import com.ultra.dit.util.Distance;

import lombok.AllArgsConstructor;

/**
 * Шаг 2.2. Класс-строитель безопасного ориентированного каркаса путей
 * (Roadmap).
 * Экземпляр создается под каждый конкретный кластер для изоляции состояния
 * ГИС-графа.
 */
public class DirectedRoadmapBuilder {

  private final LocalIndexContainer localData;
  private final List<OksObject> cluster;

  // Допуск в 5 сантиметров (0.05 метра) гарантирует сшивание при плавающей точке
  private static final double EPSILON = 0.05;
  private static final double EPSILON_SQ = EPSILON * EPSILON;

  private final GeometryFactory geometryFactory = new GeometryFactory();

  // Граф JGrapht, типизированный виртуальными узлами VirtualNode
  private final DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> graph;

  private final STRtree targetOksTree;
  private final STRtree sourceTree;

  public DirectedRoadmapBuilder(LocalIndexContainer localData, List<OksObject> cluster) {
    this.localData = localData;
    this.cluster = cluster;
    this.graph = new DirectedWeightedMultigraph<>(DefaultWeightedEdge.class);

    // Собираем все доступные целевые координаты существующих сетей (точки врезки)
    sourceTree = new STRtree();
    localData.getNodes().forEach(node -> {
      boolean isNodeSourceOrChamber = node.getProperties().getObjectType() != ObjectType.OKS_CONNECTION_POINT;
      if (isNodeSourceOrChamber) {
        var c = node.getGeometry().getCoordinate();
        sourceTree.insert(new Envelope(c), new ExistingSourceNode(c, node.getProperties()));
      }
    });

    // Собираем целевые координаты точек подключения ОКС
    targetOksTree = new STRtree();
    for (OksObject oks : cluster) {
      var c = oks.getConnectionPoint().getCoordinate();
      targetOksTree.insert(new Envelope(c), new TargeOksNode(c, oks));
    }

    sourceTree.build();
    targetOksTree.build();
    // TODO: debug start
    // STRtreeVisualizer.writeSTRtreeToFile(sourceTree,
    // "00-existingSourceTree-" + localData.hashCode());

    // STRtreeVisualizer.writeSTRtreeToFile(targetOksTree, "00-targetOksTree-" +
    // localData.hashCode());
    // TODO: debug end
  }

  /**
   * Основной метод построения графа.
   *
   * @param cluster Список ОКС текущего кластера
   * @return Построенный ориентированный граф DirectedWeightedMultigraph
   */
  public DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> build() {
    // 1. Извлекаем опорные точки геометрии для построения Скелета Вороного
    List<Coordinate> siteCoordinates = extractSkeletonSites();

    // 2. Генерация Диаграммы Вороного через JTS
    VoronoiDiagramBuilder voronoiBuilder = new VoronoiDiagramBuilder();
    voronoiBuilder.setSites(siteCoordinates);
    voronoiBuilder.setClipEnvelope(localData.getExecutionEnvelope());
    Geometry voronoiDiagram = voronoiBuilder.getDiagram(geometryFactory);

    // 3. Выделяем безопасные ребра и собираем базовые вершины каркаса
    List<LineString> skeletonEdges = filterAndExtractVoronoiEdges(voronoiDiagram);

    // ПРИНУДИТЕЛЬНОЕ СШИВАНИЕ (SNAP) ТОЧЕК К БЛИЖАЙШИМ ВЕРШИНАМ КАРКАСА
    snap(skeletonEdges);

    // 4. Наполнение графа вершинами и связями с учетом ориентации
    // (ОТ существующих сетей К точкам подключения ОКС)
    populateOrientedGraph(skeletonEdges);

    return graph;
  }

  // ПРИНУДИТЕЛЬНОЕ СШИВАНИЕ (SNAP) ТОЧЕК К БЛИЖАЙШИМ ВЕРШИНАМ КАРКАСА
  private void snap(List<LineString> skeletonEdges) {

    // Списки целевых точек
    List<Coordinate> sources = localData.getNetworksGeometryStream()
        .flatMap(g -> Arrays.stream(g.getCoordinates())).collect(Collectors.toList());
    List<Coordinate> targets = cluster.stream()
        .map(o -> o.getConnectionPoint().getCoordinate()).collect(Collectors.toList());

    // Собираем все уникальные вершины коридоров Вороного
    Set<Coordinate> skeletonVertices = new HashSet<>();
    for (LineString edge : skeletonEdges) {
      skeletonVertices.add(edge.getStartPoint().getCoordinate());
      skeletonVertices.add(edge.getEndPoint().getCoordinate());
    }

    // Соединяем каждую точку врезки (source) с ближайшей к ней безопасной вершиной
    // Вороного
    for (Coordinate src : sources) {
      Coordinate closest = findClosestCoordinate(src, skeletonVertices);
      if (closest != null) {
        skeletonEdges.add(geometryFactory.createLineString(new Coordinate[] { src, closest }));
      }
    }

    // Соединяем каждую точку ОКС (target) с ближайшей к ней безопасной вершиной
    // Вороного
    for (Coordinate trg : targets) {
      Coordinate closest = findClosestCoordinate(trg, skeletonVertices);
      if (closest != null) {
        skeletonEdges.add(geometryFactory.createLineString(new Coordinate[] { closest, trg }));
      }
    }
  }

  /**
   * Собирает опорные точки (вершины полигонов ограничений) для генерации осей
   * свободных коридоров.
   */
  private List<Coordinate> extractSkeletonSites() {
    Set<Coordinate> sites = new HashSet<>();

    // 1. Вершины локальных пространственных ограничений
    var constraintCoordinates = localData.getConstraintsGeometryStream().flatMap(constraint -> {
      // TODO: в конфиг !!! ПРОХОД РЯДОМ СО ЗДАНИЯМИ НА РАССТОЯНИИ 3 метров
      // var constraintBoundary = constraint.buffer(3.0).getBoundary();
      // return Stream.concat(Arrays.stream(constraintBoundary.getCoordinates()),
      // Arrays.stream(constraint.getCoordinates()));
      return Arrays.stream(constraint.getCoordinates());
    });

    // 2. ДОБАВЛЯЕМ ТОЧКИ СУЩЕСТВУЮЩЕЙ СЕТИ КАК СТИМУЛЫ ДЛЯ ВОРОНОГО
    var networkCoordinates = localData.getNetworksGeometryStream()
        .flatMap(network -> Arrays.stream(network.getCoordinates()));

    // 3. ДОБАВЛЯЕМ ТОЧКИ ПОДКЛЮЧЕНИЯ ОКС
    var oksCoordinates = cluster.stream().map(oks -> oks.getConnectionPoint().getCoordinate());

    var executionEnvelop = localData.getExecutionEnvelope();
    // TODO: parallel?
    var coordinates = Stream.of(constraintCoordinates, networkCoordinates, oksCoordinates)
        .parallel().flatMap(s -> s)
        .filter(c -> executionEnvelop.contains(c))
        .collect(Collectors.toList());

    sites.addAll(coordinates);

    // Во избежание пустых диаграмм при отсутствии ограничений, добавим углы
    // Bounding Box
    var geom = geometryFactory.toGeometry(executionEnvelop);
    List<Coordinate> executionEnvelopCoords = Arrays.asList(geom.getCoordinates());
    sites.addAll(executionEnvelopCoords);

    // TODO: debug
    // var geoms = sites.stream().map(c -> geometryFactory.createPoint(c))
    // .map(g -> Feature.withDefaultProperties(g));
    // var features = Stream.of(Feature.withDefaultProperties(geom));
    // DebugUtils.writeFeaturesIn32637(features, "000???-skeleton-" +
    // localData.hashCode());
    // TODO: debug end

    return new ArrayList<>(sites);
  }

  /**
   * Преобразует полигоны Вороного в набор уникальных линейных сегментов (осей
   * коридоров), отбрасывая те, которые изначально пересекают зоны запрета.
   */
  private List<LineString> filterAndExtractVoronoiEdges(Geometry voronoiDiagram) {
    List<LineString> validEdges = new ArrayList<>();

    for (int i = 0; i < voronoiDiagram.getNumGeometries(); i++) {
      Geometry polygon = voronoiDiagram.getGeometryN(i);
      // Извлекаем внешнюю границу полигона Вороного
      Coordinate[] coords = polygon.getCoordinates();

      for (int j = 0; j < coords.length - 1; j++) {
        Coordinate from = coords[j];
        Coordinate to = coords[j + 1];
        LineString segment = geometryFactory.createLineString(new Coordinate[] { from, to });

        // Первичный быстрый пре-фильтр пересечений с ограничениями.
        // Строгая валидация с буфером 2.0м будет происходить на Шаге 2.4.
        boolean intersectsConstraint = localData.getConstraintsGeometryStream()
            .anyMatch(constraint -> segment.intersects(constraint));
        if (!intersectsConstraint) {
          validEdges.add(segment);
        }
      }
    }
    return validEdges;
  }

  /**
   * Строит топологические связи в JGrapht, направляя ребра строго ОТ источников К
   * потребителям.
   */
  private void populateOrientedGraph(List<LineString> skeletonEdges) {

    // Для каждого отрезка безопасного каркаса генерируем ориентированные ребра
    for (LineString edge : skeletonEdges) {
      Coordinate c1 = edge.getStartPoint().getCoordinate();
      Coordinate c2 = edge.getEndPoint().getCoordinate();

      // Создаем виртуальные узлы со стоянием захода. NodeType выставляется как
      // VORONOI_SKELETON.
      // При необходимости, если координаты совпадают с точками врезки или ОКС, тип
      // переопределяется.
      VirtualNode node1_in = createVirtualNode(c1);
      VirtualNode node2_out = createVirtualNode(c2);

      // По ТЗ: Ориентация строго ОТ существующих сетей К ОКС.
      // Рассчитываем, какое направление приближает нас к потребителям и отдаляет от
      // источников.
      double dist1ToSources = minDistToCoordinates(c1);
      double dist2ToSources = minDistToCoordinates(c2);

      // Если движение от c1 к c2 удаляет нас от источников существующей сети,
      // направляем ребро c1 -> c2
      if (dist2ToSources >= dist1ToSources) {
        addEdgeWithNodes(node1_in, node2_out, edge);
      } else {
        addEdgeWithNodes(node2_out, node1_in, edge);
      }
    }
  }

  private VirtualNode createVirtualNode(Coordinate c) {
    // Ищем кандидатов среди sources
    List<ExistingSourceNode> sourceCandidates = getSearchCandidates(c, sourceTree);
    for (var source : sourceCandidates) {
      if (Distance.distanceSq(c, source.coordinate) <= EPSILON_SQ) {
        return VirtualNode.withFeatureProperties(c, NodeType.EXISTING_NETWORK_NODE, source.properties);
      }
    }

    // Ищем кандидатов среди targets
    List<TargeOksNode> targetCandidates = getSearchCandidates(c, targetOksTree);
    for (var target : targetCandidates) {
      if (Distance.distanceSq(c, target.coordinate) <= EPSILON_SQ) {
        return VirtualNode.withOksObject(c, NodeType.OKS_CONNECTION, target.oks);
      }
    }

    return VirtualNode.asVoronoiSkeleton(c);
  }

  private double minDistToCoordinates(Coordinate c) {
    List<ExistingSourceNode> sourceCandidates = getSearchCandidates(c, sourceTree);
    double minDist = Double.MAX_VALUE;
    for (var source : sourceCandidates) {
      double d = Distance.distanceSq(c, source.coordinate);
      if (d < minDist) {
        minDist = d;
      }
    }
    return Math.sqrt(minDist);
  }

  @SuppressWarnings("unchecked")
  private <R> List<R> getSearchCandidates(Coordinate c, STRtree searchTree) {
    var searchEnv = new Envelope(c.x - EPSILON, c.x + EPSILON, c.y - EPSILON, c.y + EPSILON);
    return searchTree.query(searchEnv);
  }

  private Coordinate findClosestCoordinate(Coordinate target, Set<Coordinate> candidates) {
    Coordinate closest = null;
    double minDist = Double.MAX_VALUE;
    for (Coordinate c : candidates) {
      double d = Distance.distanceSq(c, target);
      if (d < minDist) {
        minDist = d;
        closest = c;
      }
    }
    return closest;
  }

  private void addEdgeWithNodes(VirtualNode from, VirtualNode to, LineString edgeGeometry) {
    graph.addVertex(from);
    graph.addVertex(to);

    // Добавляем ребро. Расчет весов с учетом углов поворота (Шаг 2.3)
    // и коллизий с буфером 2.0м (Шаг 2.4) будет навешан на калькулятор весов
    // JGrapht.
    DefaultWeightedEdge edge = graph.addEdge(from, to);
    if (edge != null) {
      // Инициализируем базовым весом (физическая длина)
      graph.setEdgeWeight(edge, edgeGeometry.getLength());
    }
  }

  @AllArgsConstructor
  private static class ExistingSourceNode {
    final Coordinate coordinate;
    final FeatureProperties properties;
  }

  @AllArgsConstructor
  private static class TargeOksNode {
    final Coordinate coordinate;
    final OksObject oks;
  }
}
