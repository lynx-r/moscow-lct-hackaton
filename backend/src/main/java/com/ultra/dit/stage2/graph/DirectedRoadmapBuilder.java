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
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder;

import com.ultra.dit.stage2.model.LocalIndexContainer;
import com.ultra.dit.stage2.model.NodeType;
import com.ultra.dit.stage2.model.OksObject;
import com.ultra.dit.stage2.model.Vector2D;
import com.ultra.dit.stage2.model.VirtualNode;

/**
 * Шаг 2.2. Класс-строитель безопасного ориентированного каркаса путей
 * (Roadmap).
 * Экземпляр создается под каждый конкретный кластер для изоляции состояния
 * ГИС-графа.
 */
public class DirectedRoadmapBuilder {

  private final LocalIndexContainer localData;
  private final GeometryFactory geometryFactory = new GeometryFactory();

  // Граф JGrapht, типизированный виртуальными узлами VirtualNode
  private final DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> graph;

  public DirectedRoadmapBuilder(LocalIndexContainer localData) {
    this.localData = localData;
    this.graph = new DirectedWeightedMultigraph<>(DefaultWeightedEdge.class);
  }

  /**
   * Основной метод построения графа.
   *
   * @param cluster Список ОКС текущего кластера
   * @return Построенный ориентированный граф DirectedWeightedMultigraph
   */
  public DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> build(List<OksObject> cluster,
      /* debug */ int idx) {
    // 1. Извлекаем опорные точки геометрии для построения Скелета Вороного
    List<Coordinate> siteCoordinates = extractSkeletonSites(cluster);

    // // TODO: debug start
    // var sitePoints = siteCoordinates.stream().map(t ->
    // geometryFactory.createPoint(t));
    // var siteFeatures = FeatureMapper.mapStreamGeometriesToFeatures(sitePoints);
    // DebugUtils.transformAndWriteFeatureCollectionToFile(siteFeatures, "2-" + idx
    // + "-skeletonSites.geojson");
    // // TODO: debug stop

    // 2. Генерация Диаграммы Вороного через JTS
    VoronoiDiagramBuilder voronoiBuilder = new VoronoiDiagramBuilder();
    voronoiBuilder.setSites(siteCoordinates);
    voronoiBuilder.setClipEnvelope(localData.getExecutionEnvelope());
    Geometry voronoiDiagram = voronoiBuilder.getDiagram(geometryFactory);

    // Списки целевых точек
    List<Coordinate> sources = localData.getNetworks().stream()
        .flatMap(g -> Arrays.stream(g.getCoordinates())).collect(Collectors.toList());
    List<Coordinate> targets = cluster.stream()
        .map(o -> o.getConnectionPoint().getCoordinate()).collect(Collectors.toList());

    // 3. Выделяем безопасные ребра и собираем базовые вершины каркаса
    List<LineString> skeletonEdges = filterAndExtractVoronoiEdges(voronoiDiagram);

    // ПРИНУДИТЕЛЬНОЕ СШИВАНИЕ (SNAP) ТОЧЕК К БЛИЖАЙШИМ ВЕРШИНАМ КАРКАСА
    snap(skeletonEdges, sources, targets);

    // 4. Наполнение графа вершинами и связями с учетом ориентации
    // (ОТ существующих сетей К точкам подключения ОКС)
    populateOrientedGraph(skeletonEdges, cluster);

    // // TODO: debug start
    // var skeletonEdgesFeatures =
    // FeatureMapper.mapGeometriesToFeatures(skeletonEdges);
    // DebugUtils.transformAndWriteFeatureCollectionToFile(skeletonEdgesFeatures,
    // "3-" + idx + "-skeletonEdgesFeatureCollection.geojson");
    // // TODO: debug stop

    return graph;
  }

  // ПРИНУДИТЕЛЬНОЕ СШИВАНИЕ (SNAP) ТОЧЕК К БЛИЖАЙШИМ ВЕРШИНАМ КАРКАСА
  private void snap(List<LineString> skeletonEdges, List<Coordinate> sources, List<Coordinate> targets) {

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
  private List<Coordinate> extractSkeletonSites(List<OksObject> cluster) {
    Set<Coordinate> sites = new HashSet<>();

    // 1. Вершины локальных пространственных ограничений
    for (Geometry constraint : localData.getConstraints()) {
      for (Coordinate coord : constraint.getCoordinates()) {
        sites.add(coord);
      }
    }

    // 2. ДОБАВЛЯЕМ ТОЧКИ СУЩЕСТВУЮЩЕЙ СЕТИ КАК СТИМУЛЫ ДЛЯ ВОРОНОГО
    for (Geometry network : localData.getNetworks()) {
      for (Coordinate coord : network.getCoordinates()) {
        sites.add(coord);
      }
    }

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
        boolean intersectsConstraint = false;
        for (Geometry constraint : localData.getConstraints()) {
          if (segment.intersects(constraint)) {
            intersectsConstraint = true;
            break;
          }
        }
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
  private void populateOrientedGraph(List<LineString> skeletonEdges, List<OksObject> cluster) {
    // Собираем все доступные целевые координаты существующих сетей (точки врезки)
    List<Coordinate> existingSourceCoords = new ArrayList<>();
    for (Geometry network : localData.getNetworks()) {
      for (Coordinate c : network.getCoordinates()) {
        existingSourceCoords.add(c);
      }
    }

    // Собираем целевые координаты точек подключения ОКС
    List<Coordinate> targetOksCoords = new ArrayList<>();
    for (OksObject oks : cluster) {
      targetOksCoords.add(oks.getConnectionPoint().getCoordinate());
    }

    // TODO: debug start
    // System.out.println("skeletonEdges:");
    // skeletonEdges.stream()
    // .forEach(t -> {
    // System.out.println(t.getStartPoint().getCoordinate());
    // System.out.println(t.getEndPoint().getCoordinate());
    // });
    // System.out.println("-----");
    // TODO: debug end

    // Для каждого отрезка безопасного каркаса генерируем ориентированные ребра
    for (LineString edge : skeletonEdges) {
      Coordinate c1 = edge.getStartPoint().getCoordinate();
      Coordinate c2 = edge.getEndPoint().getCoordinate();

      // Вычисляем базовые векторы направления
      Vector2D dir1to2 = new Vector2D(c2.x - c1.x, c2.y - c1.y);
      Vector2D dir2to1 = new Vector2D(c1.x - c2.x, c1.y - c2.y);

      // Создаем виртуальные узлы со стоянием захода. NodeType выставляется как
      // VORONOI_SKELETON.
      // При необходимости, если координаты совпадают с точками врезки или ОКС, тип
      // переопределяется.

      // TODO: prioritet 1 не выполняется определения типа узла determineNodeType
      VirtualNode node1_in = new VirtualNode(c1, dir2to1, determineNodeType(c1, existingSourceCoords, targetOksCoords));
      VirtualNode node2_out = new VirtualNode(c2, dir1to2,
          determineNodeType(c2, existingSourceCoords, targetOksCoords));

      // По ТЗ: Ориентация строго ОТ существующих сетей К ОКС.
      // Рассчитываем, какое направление приближает нас к потребителям и отдаляет от
      // источников.
      double dist1ToSources = minDistToCoordinates(c1, existingSourceCoords);
      double dist2ToSources = minDistToCoordinates(c2, existingSourceCoords);

      // Если движение от c1 к c2 удаляет нас от источников существующей сети,
      // направляем ребро c1 -> c2
      if (dist2ToSources >= dist1ToSources) {
        addEdgeWithNodes(node1_in, node2_out, edge);
      } else {
        addEdgeWithNodes(node2_out, node1_in, edge);
      }
    }
  }

  private NodeType determineNodeType(Coordinate c, List<Coordinate> sources, List<Coordinate> targets) {
    if (targets.contains(c)) {
      return NodeType.OKS_CONNECTION;
    }
    if (sources.contains(c)) {
      return NodeType.EXISTING_NETWORK_NODE;
    }
    return NodeType.VORONOI_SKELETON;
  }

  private double minDistToCoordinates(Coordinate c, List<Coordinate> points) {
    if (points.isEmpty())
      return 0.0;
    double minDist = Double.MAX_VALUE;
    for (Coordinate p : points) {
      double d = c.distance(p);
      if (d < minDist)
        minDist = d;
    }
    return minDist;
  }

  // FIXME: new
  private Coordinate findClosestCoordinate(Coordinate target, Set<Coordinate> candidates) {
    Coordinate closest = null;
    double minDist = Double.MAX_VALUE;
    for (Coordinate c : candidates) {
      double d = target.distance(c);
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
