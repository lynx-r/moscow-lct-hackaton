package com.ultra.dit.stage2.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.YenKShortestPath;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ultra.dit.aop.LogExecutionTime;
import com.ultra.dit.stage2.graph.EdgeWeightCalculator;
import com.ultra.dit.stage2.model.NodeType;
import com.ultra.dit.stage2.model.OksObject;
import com.ultra.dit.stage2.model.RoutingResult;
import com.ultra.dit.stage2.model.VirtualNode;

import lombok.extern.log4j.Log4j2;

/**
 * Шаг 2.5. Финальный сервис генерации вариантов трассировки,
 * контроля валентности узлов и построения топологии GeoJSON-совместимых
 * объектов.
 */
@Log4j2
@Service
public class NetworkTopologyGeneratorService {

  private final GeometryFactory geometryFactory = new GeometryFactory();

  private final int maxVariants;
  private final int maxValence;
  private final int chooseFromPathsCount;

  // TODO: в конфиг
  // 5 ближайших точек врезки более чем достаточно для генерации 3 вариантов!
  private static final int CLOSES_SOURCES_COUNT = 5;
  // Критерий уникальности: если более 70% вершин совпадают, считаем варианты
  // идентичными
  private final double UNIQUE_PERCENT = 0.7;

  private final EdgeWeightCalculator angleCalculator;

  public NetworkTopologyGeneratorService(
      @Value("${app.solution.stage2.maxVariants:3}") int maxVariants,
      @Value("${app.solution.stage2.maxValence:4}") int maxValence,
      @Value("${app.solution.stage2.chooseFromPathsCount:5}") int chooseFromPathsCount) {
    this.maxVariants = maxVariants;
    this.maxValence = maxValence;
    this.chooseFromPathsCount = chooseFromPathsCount;
    this.angleCalculator = new EdgeWeightCalculator();
  }

  /**
   * Генерирует до 3 содержательно отличающихся вариантов прокладки тепловой сети
   * для кластера ОКС.
   */
  @LogExecutionTime
  @SuppressWarnings("null")
  public List<RoutingResult> generateNetworkVariants(
      List<OksObject> cluster,
      DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> graph) {

    List<GraphPath<VirtualNode, DefaultWeightedEdge>> rawPaths = getRawPaths(graph, cluster);

    if (rawPaths.isEmpty()) {
      return List.of();
    }

    // Динамическая фильтрация и ранжирование путей с учётом геометрии углов
    List<EvaluatedPath> evaluatedPaths = getEvaluatedPaths(rawPaths);

    // Сортируем варианты по итоговой стоимости (Длина + Штрафы за углы)
    evaluatedPaths.sort(Comparator.comparingDouble(p -> p.totalCost));

    var distinctPaths = filterDistinctVariants(evaluatedPaths);

    // Преобразуем выбранные пути в геоинформационные структуры данных
    List<RoutingResult> finalVariants = getFinalVariants(distinctPaths);

    // Если не удалось проложить ни одного пути, фиксируем кластер как
    // неподключенный
    if (finalVariants.isEmpty()) {
      return List.of();
    }

    return finalVariants;
  }

  private List<GraphPath<VirtualNode, DefaultWeightedEdge>> getRawPaths(
      DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> graph, List<OksObject> cluster) {
    // Оставляем только топ-5 геометрически ближайших камер врезки к потребителям
    // кластера
    // Coordinate clusterCenter =
    // cluster.get(0).getConnectionPoint().getCoordinate();

    // Находим все потенциальные стартовые узлы (точки существующей сети) и финишные
    // (ОКС)
    List<VirtualNode> sourceNodes = graph.vertexSet().stream()
        .filter(v -> v.getType() == NodeType.EXISTING_NETWORK_NODE)
        .collect(Collectors.toList());
    log.info("SourceNodes Count: " + sourceNodes.size());

    // TODO: раскомментировать?
    // List<VirtualNode> closestSources = sourceNodes.stream()
    // .sorted(Comparator.comparingDouble(v ->
    // v.getCoordinate().distance(clusterCenter)))
    // .limit(CLOSES_SOURCES_COUNT) // 5 ближайших точек врезки более чем достаточно
    // для генерации 3 вариантов!
    // .collect(Collectors.toList());

    List<VirtualNode> targetNodes = graph.vertexSet().stream()
        .filter(v -> v.getType() == NodeType.OKS_CONNECTION)
        .collect(Collectors.toList());
    log.info("TargetNodes Count: " + targetNodes.size());

    if (sourceNodes.isEmpty() || targetNodes.isEmpty()) {
      return List.of();
    }

    // if (log.isDebugEnabled()) {
    // TODO: debug start
    // System.out.println("=== ДЕБАГ ТРАССИРОВКИ ===");
    // System.out.println("Потенциальные источники (Sources): " +
    // sourceNodes.stream()
    // .map(v -> "[" + v.getCoordinate().x + ", " + v.getCoordinate().y + "] тип: "
    // + v.getType())
    // .collect(Collectors.joining(", ")));

    // System.out.println("Потенциальные цели (Targets): " + targetNodes.stream()
    // .map(v -> "[" + v.getCoordinate().x + ", " + v.getCoordinate().y + "] тип: "
    // + v.getType())
    // .collect(Collectors.joining(", ")));
    // TODO: debug stop
    // }

    // Используем алгоритм Йена из JGrapht для поиска простых путей без петель
    var pathFinder = new YenKShortestPath<>(graph);

    // Собираем альтернативные пути для всех комбинаций источников и потребителей
    // кластера
    // AtomicInteger counter = new AtomicInteger(0);
    var rawPaths = new ArrayList<GraphPath<VirtualNode, DefaultWeightedEdge>>();
    for (VirtualNode source : sourceNodes) {
      for (VirtualNode target : targetNodes) {
        try {
          // if (log.isDebugEnabled()) {
          // TODO: debug start
          // Выводим конкретную пару, для которой сейчас запустится YenKShortestPath
          // System.out.println("Запуск поиска от Source: [" + source.getCoordinate().x +
          // ", " + source.getCoordinate().y
          // + "] тип: " + source.getType());
          // System.out.println("Запуск поиска к Target: [" + target.getCoordinate().x +
          // ", " + target.getCoordinate().y
          // + "] тип: " + target.getType());
          // System.out.println("===============================");
          // TODO: debug stop
          // }

          // Запрашиваем избыточное количество путей (например, 10), чтобы выбрать 3
          // структурно уникальных
          // int i = counter.incrementAndGet();
          var paths = pathFinder.getPaths(source, target, chooseFromPathsCount);

          // if (log.isDebugEnabled()) {
          // TODO: debug start
          // DebugUtils.writeGraphPaths(paths, "path-" + clusterIdx + "-" + i);
          // TODO: debug stop
          // }

          if (!paths.isEmpty()) {
            rawPaths.addAll(paths);
          }
        } catch (IllegalArgumentException ignored) {
          // Вызывается JGrapht, если компоненты графа изолированы
        }
      }
    }

    return rawPaths;
  }

  private List<RoutingResult> getFinalVariants(List<EvaluatedPath> distinctPaths) {
    List<RoutingResult> finalVariants = new ArrayList<>();
    for (var evalPath : distinctPaths) {
      RoutingResult result = buildTopologicalResult(evalPath.path);
      if (result.isConnected()) {
        finalVariants.add(result);
        if (finalVariants.size() >= maxVariants) {
          break;
        }
      }
    }
    return finalVariants;
  }

  private List<EvaluatedPath> getEvaluatedPaths(List<GraphPath<VirtualNode, DefaultWeightedEdge>> rawPaths) {
    List<EvaluatedPath> evaluatedPaths = new ArrayList<>();
    for (var path : rawPaths) {
      List<VirtualNode> vertices = path.getVertexList();
      if (vertices.size() < 2)
        continue;

      double totalWeightWithPenalties = 0.0;
      boolean validationPassed = true;

      // Накапливаем физическую длину
      totalWeightWithPenalties += path.getWeight();

      // Динамический расчёт изломов по цепочке смежных рёбер
      for (int i = 0; i < vertices.size() - 2; i++) {
        Coordinate p1 = vertices.get(i).getCoordinate();
        Coordinate p2 = vertices.get(i + 1).getCoordinate(); // Точка излома
        Coordinate p3 = vertices.get(i + 2).getCoordinate();

        double penalty = angleCalculator.calculateWeight(p1, p2, p3);

        if (penalty == Double.MAX_VALUE) {
          validationPassed = false; // Путь совершил запрещенный разворот >90 градусов!
          break;
        }
        totalWeightWithPenalties += penalty;
      }

      if (validationPassed) {
        evaluatedPaths.add(new EvaluatedPath(path, totalWeightWithPenalties));
      }
    }
    return evaluatedPaths;
  }

  /**
   * Фильтрует пути, отсекая косметические дубликаты. Варианты должны быть
   * содержательно разными
   * (проходить через разные свободные коридоры Вороного или точки врезки).
   */
  @SuppressWarnings("null")
  private List<EvaluatedPath> filterDistinctVariants(List<EvaluatedPath> paths) {
    List<EvaluatedPath> distinct = new ArrayList<>();
    for (var candidate : paths) {
      boolean isSimilar = false;
      Set<Coordinate> candidateCoords = candidate.path.getVertexList().stream()
          .map(VirtualNode::getCoordinate).collect(Collectors.toSet());

      for (var accepted : distinct) {
        long sharedVertices = accepted.path.getVertexList().stream()
            .filter(v -> candidateCoords.contains(v.getCoordinate())).count();

        // Критерий уникальности: если более 70% вершин совпадают, считаем варианты
        // идентичными
        double overlap = (double) sharedVertices
            / Math.max(candidate.path.getVertexList().size(), accepted.path.getVertexList().size());
        if (overlap > UNIQUE_PERCENT) {
          isSimilar = true;
          break;
        }
      }
      if (!isSimilar)
        distinct.add(candidate);
    }
    return distinct;
  }

  /**
   * Строит итоговый валидный граф трассы, проверяет валентность и расставляет
   * камеры.
   */
  private RoutingResult buildTopologicalResult(
      GraphPath<VirtualNode, DefaultWeightedEdge> path) {

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
      // TODO: проверить
      // TODO: Контроль валентности камер (degree <= 4) по ТЗ
      // TODO: if (valenceMap.get(c1) > 4 || valenceMap.get(c2) > 4) {
      if (totalValence > maxValence) {
        return new RoutingResult(List.of(), List.of(), false);
      }

      var line = geometryFactory.createLineString(new Coordinate[] { c1, c2 });
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

  private static class EvaluatedPath {
    final GraphPath<VirtualNode, DefaultWeightedEdge> path;
    final double totalCost;

    EvaluatedPath(GraphPath<VirtualNode, DefaultWeightedEdge> path, double totalCost) {
      this.path = path;
      this.totalCost = totalCost;
    }
  }
}
