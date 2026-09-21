package com.ultra.dit.service.stage2.graph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder;

import com.ultra.dit.dto.stage2.LocalIndexContainer;
import com.ultra.dit.dto.stage2.NodeType;
import com.ultra.dit.dto.stage2.OksObject;
import com.ultra.dit.dto.stage2.Vector2D;
import com.ultra.dit.dto.stage2.VirtualNode;
import com.ultra.dit.service.stage2.CollisionDetectionService;

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
  public DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> build(List<OksObject> cluster) {
    // 1. Извлекаем опорные точки геометрии для построения Скелета Вороного
    List<Coordinate> siteCoordinates = extractSkeletonSites(cluster);

    // 2. Генерация Диаграммы Вороного через JTS
    VoronoiDiagramBuilder voronoiBuilder = new VoronoiDiagramBuilder();
    voronoiBuilder.setSites(siteCoordinates);
    voronoiBuilder.setClipEnvelope(localData.getExecutionEnvelope());
    Geometry voronoiDiagram = voronoiBuilder.getDiagram(geometryFactory);

    // 3. Выделяем безопасные ребра и собираем базовые вершины каркаса
    List<LineString> skeletonEdges = filterAndExtractVoronoiEdges(voronoiDiagram);

    // 4. Наполнение графа вершинами и связями с учетом ориентации
    // (ОТ существующих сетей К точкам подключения ОКС)
    populateOrientedGraph(skeletonEdges, cluster);

    return this.graph;
  }

  /**
   * Собирает опорные точки (вершины полигонов ограничений) для генерации осей
   * свободных коридоров.
   */
  private List<Coordinate> extractSkeletonSites(List<OksObject> cluster) {
    Set<Coordinate> sites = new HashSet<>();

    // Вершины локальных пространственных ограничений — основные источники для
    // диаграммы
    for (Geometry constraint : localData.getConstraints()) {
      for (Coordinate coord : constraint.getCoordinates()) {
        sites.add(coord);
      }
    }

    // Во избежание пустых диаграмм при отсутствии ограничений, добавим углы
    // Bounding Box
    Envelope env = localData.getExecutionEnvelope();
    sites.add(new Coordinate(env.getMinX(), env.getMinY()));
    sites.add(new Coordinate(env.getMaxX(), env.getMinY()));
    sites.add(new Coordinate(env.getMinX(), env.getMaxY()));
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
    if (targets.contains(c))
      return NodeType.OKS_CONNECTION;
    if (sources.contains(c))
      return NodeType.EXISTING_NETWORK_NODE;
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
