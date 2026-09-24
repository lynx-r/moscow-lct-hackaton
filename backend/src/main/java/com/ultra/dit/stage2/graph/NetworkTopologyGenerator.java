package com.ultra.dit.stage2.graph;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.YenKShortestPath;
import org.jgrapht.graph.AsUndirectedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.locationtech.jts.geom.*;
import org.springframework.stereotype.Service;

import com.ultra.dit.service.DebugUtils;
import com.ultra.dit.stage2.model.NodeType;
import com.ultra.dit.stage2.model.OksObject;
import com.ultra.dit.stage2.model.RoutingResult;
import com.ultra.dit.stage2.model.VirtualNode;

import lombok.NoArgsConstructor;
import lombok.extern.java.Log;
import lombok.extern.log4j.Log4j;
import lombok.extern.log4j.Log4j2;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Шаг 2.5. Финальный сервис генерации вариантов трассировки,
 * контроля валентности узлов и построения топологии GeoJSON-совместимых
 * объектов.
 */
@Log4j2
@Service
@NoArgsConstructor
public class NetworkTopologyGenerator {

  private final GeometryFactory geometryFactory = new GeometryFactory();
  private static final int MAX_VARIANTS = 3;
  private static final int MAX_VALENCE = 4; // Максимальная степень узла для камер по ТЗ

  /**
   * Генерирует до 3 содержательно отличающихся вариантов прокладки тепловой сети
   * для кластера ОКС.
   */
  @SuppressWarnings("null")
  public List<RoutingResult> generateNetworkVariants(
      List<OksObject> cluster,
      DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> graph) {

    List<RoutingResult> finalVariants = new ArrayList<>();

    // Находим все потенциальные стартовые узлы (точки существующей сети) и финишные
    // (ОКС)
    List<VirtualNode> sourceNodes = graph.vertexSet().stream()
        .filter(v -> v.getType() == NodeType.EXISTING_NETWORK_NODE)
        .collect(Collectors.toList());

    List<VirtualNode> targetNodes = graph.vertexSet().stream()
        .filter(v -> v.getType() == NodeType.OKS_CONNECTION)
        .collect(Collectors.toList());

    if (log.isDebugEnabled()) {
      // TODO: debug start
      System.out.println("=== ДЕБАГ ТРАССИРОВКИ ===");
      System.out.println("Потенциальные источники (Sources): " + sourceNodes.stream()
          .map(v -> "[" + v.getCoordinate().x + ", " + v.getCoordinate().y + "] тип: " + v.getType())
          .collect(Collectors.joining(", ")));

      System.out.println("Потенциальные цели (Targets): " + targetNodes.stream()
          .map(v -> "[" + v.getCoordinate().x + ", " + v.getCoordinate().y + "] тип: " + v.getType())
          .collect(Collectors.joining(", ")));
      // TODO: debug stop
    }

    if (sourceNodes.isEmpty() || targetNodes.isEmpty()) {
      return List.of(new RoutingResult(List.of(), List.of(), false));
    }

    // Используем алгоритм Йена из JGrapht для поиска простых путей без петель
    // TODO: debug undirected graph
    var undirectedGraph = new AsUndirectedGraph<>(graph);
    YenKShortestPath<VirtualNode, DefaultWeightedEdge> pathFinder = new YenKShortestPath<>(undirectedGraph);

    // Собираем альтернативные пути для всех комбинаций источников и потребителей
    // кластера
    AtomicInteger counter = new AtomicInteger(0);
    List<GraphPath<VirtualNode, DefaultWeightedEdge>> rawPaths = new ArrayList<>();
    for (VirtualNode source : sourceNodes) {
      for (VirtualNode target : targetNodes) {
        try {
          if (log.isDebugEnabled()) {
            // TODO: debug start
            // Выводим конкретную пару, для которой сейчас запустится YenKShortestPath
            System.out.println("Запуск поиска от Source: [" + source.getCoordinate().x + ", " + source.getCoordinate().y
                + "] тип: " + source.getType());
            System.out.println("Запуск поиска к Target: [" + target.getCoordinate().x + ", " + target.getCoordinate().y
                + "] тип: " + target.getType());
            System.out.println("===============================");
            // TODO: debug stop
          }

          // Запрашиваем избыточное количество путей (например, 10), чтобы выбрать 3
          // структурно уникальных
          int i = counter.incrementAndGet();
          List<GraphPath<VirtualNode, DefaultWeightedEdge>> paths = pathFinder.getPaths(source, target, 10);

          if (log.isDebugEnabled()) {
            // TODO: debug start
            DebugUtils.writeGraphPaths(paths, "path-" + i);
            // TODO: debug stop
          }

          if (!paths.isEmpty()) {
            rawPaths.addAll(paths);
          }
        } catch (IllegalArgumentException ignored) {
          // Вызывается JGrapht, если компоненты графа изолированы
        }
      }
    }

    // Сортируем пути по базовой стоимости (весу)
    rawPaths.sort(Comparator.comparingDouble(GraphPath::getWeight));

    List<GraphPath<VirtualNode, DefaultWeightedEdge>> uniquePaths = filterDistinctVariants(rawPaths);

    // Преобразуем выбранные пути в геоинформационные структуры данных
    for (GraphPath<VirtualNode, DefaultWeightedEdge> path : uniquePaths) {
      RoutingResult result = buildTopologicalResult(path, graph);
      if (result.isConnected()) {
        finalVariants.add(result);
        if (finalVariants.size() >= MAX_VARIANTS) {
          break;
        }
      }
    }

    // Если не удалось проложить ни одного пути, фиксируем кластер как
    // неподключенный
    if (finalVariants.isEmpty()) {
      return List.of(new RoutingResult(List.of(), List.of(), false));
    }

    return finalVariants;
  }

  /**
   * Фильтрует пути, отсекая косметические дубликаты. Варианты должны быть
   * содержательно разными
   * (проходить через разные свободные коридоры Вороного или точки врезки).
   */
  private List<GraphPath<VirtualNode, DefaultWeightedEdge>> filterDistinctVariants(
      List<GraphPath<VirtualNode, DefaultWeightedEdge>> sortedPaths) {

    List<GraphPath<VirtualNode, DefaultWeightedEdge>> distinct = new ArrayList<>();

    for (GraphPath<VirtualNode, DefaultWeightedEdge> candidate : sortedPaths) {
      if (candidate.getWeight() >= Double.MAX_VALUE)
        continue;

      boolean isDuplicate = false;
      @SuppressWarnings("null")
      Set<Coordinate> candidateCoords = candidate.getVertexList().stream()
          .map(VirtualNode::getCoordinate)
          .collect(Collectors.toSet());

      for (GraphPath<VirtualNode, DefaultWeightedEdge> accepted : distinct) {
        int sharedVertices = 0;
        for (VirtualNode v : accepted.getVertexList()) {
          if (candidateCoords.contains(v.getCoordinate())) {
            sharedVertices++;
          }
        }
        // Критерий уникальности: если более 70% вершин совпадают, считаем варианты
        // идентичными
        double overlapRatio = (double) sharedVertices
            / Math.max(candidate.getVertexList().size(), accepted.getVertexList().size());
        if (overlapRatio > 0.7) {
          isDuplicate = true;
          break;
        }
      }

      if (!isDuplicate) {
        distinct.add(candidate);
      }
    }
    return distinct;
  }

  /**
   * Строит итоговый валидный граф трассы, проверяет валентность и расставляет
   * камеры.
   */
  private RoutingResult buildTopologicalResult(
      GraphPath<VirtualNode, DefaultWeightedEdge> path,
      DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> originalGraph) {

    List<VirtualNode> vertices = path.getVertexList();
    List<LineString> pipelines = new ArrayList<>();
    List<Point> chambers = new ArrayList<>();

    // Карта для подсчета валентности узлов (degree) в рамках выбранного решения
    Map<Coordinate, Integer> nodeValence = new HashMap<>();

    // Сборка линейных объектов и первичный подсчет степеней вершин
    for (int i = 0; i < vertices.size() - 1; i++) {
      VirtualNode from = vertices.get(i);
      VirtualNode to = vertices.get(i + 1);

      Coordinate c1 = from.getCoordinate();
      Coordinate c2 = to.getCoordinate();

      nodeValence.put(c1, nodeValence.getOrDefault(c1, 0) + 1);
      nodeValence.put(c2, nodeValence.getOrDefault(c2, 0) + 1);

      // Строгий контроль валентности: если узел перегружен (>= 4 подключений), трасса
      // бракуется
      // Учитываем связи оригинального графа в точке стыка, если это существующая
      // камера
      int totalValence = nodeValence.get(c1) + (from.getType() == NodeType.EXISTING_NETWORK_NODE ? 2 : 0);
      if (totalValence > MAX_VALENCE) {
        return new RoutingResult(List.of(), List.of(), false);
      }

      LineString line = geometryFactory.createLineString(new Coordinate[] { c1, c2 });
      pipelines.add(line);
    }

    // Проставление типов узлов согласно ТЗ (Камеры в точках смены атрибутов или
    // ветвления)
    for (VirtualNode node : vertices) {
      Coordinate coord = node.getCoordinate();
      int currentValence = nodeValence.getOrDefault(coord, 0);

      // Если узел является точкой слияния/разветвления (валентность > 2)
      // или это конечная точка ОКС/существующая камера — это объект CHAMBER
      if (currentValence > 2 || node.getType() == NodeType.OKS_CONNECTION
          || node.getType() == NodeType.EXISTING_NETWORK_NODE) {
        chambers.add(geometryFactory.createPoint(coord));
      } else if (node.getType() == NodeType.VORONOI_SKELETON && currentValence == 2) {
        // По ТЗ: Изломы без смены параметров остаются просто вершинами внутри
        // LineString.
        // Если здесь начинается/заканчивается специальный проход (Шаг 2.4), создается
        // technical_node
        // (логику деления на обычные/специальные участки можно навесить на метаданные
        // ребра).
      }
    }

    return new RoutingResult(pipelines, chambers, true);
  }
}
