package com.ultra.dit.stage2.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder;

import com.ultra.dit.stage2.model.LocalIndexContainer;
import com.ultra.dit.stage2.model.NodeType;
import com.ultra.dit.stage2.model.OksObject;
import com.ultra.dit.stage2.model.VirtualNode;
import com.ultra.dit.util.Distance;

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
  private final STRtree existingSourceTree;

  public DirectedRoadmapBuilder(LocalIndexContainer localData, List<OksObject> cluster) {
    this.localData = localData;
    this.cluster = cluster;
    this.graph = new DirectedWeightedMultigraph<>(DefaultWeightedEdge.class);

    // Собираем все доступные целевые координаты существующих сетей (точки врезки)
    existingSourceTree = new STRtree();
    localData.getNetworksGeometryStream().forEach(network -> {
      for (Coordinate c : network.getCoordinates()) {
        existingSourceTree.insert(new Envelope(c), c);
      }
    });

    // Собираем целевые координаты точек подключения ОКС
    targetOksTree = new STRtree();
    for (OksObject oks : cluster) {
      var c = oks.getConnectionPoint().getCoordinate();
      targetOksTree.insert(new Envelope(c), c);
    }
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
    localData.getConstraintsGeometryStream().forEach(constraint -> {
      // TODO: в конфиг !!!
      var constraintBoundary = constraint.buffer(3.0).getBoundary();
      for (Coordinate coord : constraintBoundary.getCoordinates()) {
        sites.add(coord);
      }
      for (Coordinate coord : constraint.getCoordinates()) {
        sites.add(coord);
      }
    });

    // 2. ДОБАВЛЯЕМ ТОЧКИ СУЩЕСТВУЮЩЕЙ СЕТИ КАК СТИМУЛЫ ДЛЯ ВОРОНОГО
    localData.getNetworksGeometryStream().forEach(network -> {
      for (Coordinate coord : network.getCoordinates()) {
        sites.add(coord);
      }
    });

    // 3. ДОБАВЛЯЕМ ТОЧКИ ПОДКЛЮЧЕНИЯ ОКС
    for (OksObject oks : cluster) {
      sites.add(oks.getConnectionPoint().getCoordinate());
    }

    // Во избежание пустых диаграмм при отсутствии ограничений, добавим углы
    // Bounding Box
    Envelope env = localData.getExecutionEnvelope();
    // левый нижний угол
    sites.add(new Coordinate(env.getMinX(), env.getMinY()));
    // правый нижний угол
    sites.add(new Coordinate(env.getMaxX(), env.getMinY()));
    // левый верхний угол
    sites.add(new Coordinate(env.getMinX(), env.getMaxY()));
    // правый верхний угол
    sites.add(new Coordinate(env.getMaxX(), env.getMaxY()));

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

      VirtualNode node1_in = new VirtualNode(c1, determineNodeType(c1, existingSourceTree, targetOksTree));
      VirtualNode node2_out = new VirtualNode(c2,
          determineNodeType(c2, existingSourceTree, targetOksTree));

      // По ТЗ: Ориентация строго ОТ существующих сетей К ОКС.
      // Рассчитываем, какое направление приближает нас к потребителям и отдаляет от
      // источников.
      double dist1ToSources = minDistToCoordinates(c1, existingSourceTree);
      double dist2ToSources = minDistToCoordinates(c2, existingSourceTree);

      // Если движение от c1 к c2 удаляет нас от источников существующей сети,
      // направляем ребро c1 -> c2
      if (dist2ToSources >= dist1ToSources) {
        addEdgeWithNodes(node1_in, node2_out, edge);
      } else {
        addEdgeWithNodes(node2_out, node1_in, edge);
      }
    }
  }

  private NodeType determineNodeType(Coordinate c, STRtree existingSourceTree, STRtree targetOksTree) {
    // Ищем кандидатов среди sources
    List<Coordinate> sourceCandidates = getSearchCandidates(c, existingSourceTree);
    for (Coordinate source : sourceCandidates) {
      if (Distance.distanceSq(c, source) <= EPSILON_SQ) {
        return NodeType.EXISTING_NETWORK_NODE;
      }
    }

    // Ищем кандидатов среди targets
    List<Coordinate> targetCandidates = getSearchCandidates(c, targetOksTree);
    for (Coordinate target : targetCandidates) {
      if (Distance.distanceSq(c, target) <= EPSILON_SQ) {
        return NodeType.OKS_CONNECTION;
      }
    }

    return NodeType.VORONOI_SKELETON;
  }

  private double minDistToCoordinates(Coordinate c, STRtree existingSourceTree) {
    List<Coordinate> sourceCandidates = getSearchCandidates(c, existingSourceTree);
    double minDist = Double.MAX_VALUE;
    for (Coordinate p : sourceCandidates) {
      double d = Distance.distanceSq(c, p);
      if (d < minDist) {
        minDist = d;
      }
    }
    return Math.sqrt(minDist);
  }

  @SuppressWarnings("unchecked")
  private List<Coordinate> getSearchCandidates(Coordinate c, STRtree searchTree) {
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
}
