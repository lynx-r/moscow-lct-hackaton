package com.ultra.dit.stage2.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.YenKShortestPath;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.dto.geojson.properties.HeatNetworkProperties;
import com.ultra.dit.dto.geojson.properties.VariantSummaryProperties;
import com.ultra.dit.stage2.config.Stage2Properties;
import com.ultra.dit.stage2.graph.EdgeWeightCalculator;
import com.ultra.dit.stage2.model.NodeType;
import com.ultra.dit.stage2.model.OksObject;
import com.ultra.dit.stage2.model.RoutingResult;
import com.ultra.dit.stage2.model.VirtualNode;
import com.ultra.dit.util.DebugUtils;
import com.ultra.dit.util.Distance;
import com.ultra.dit.util.Id;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Шаг 2.5. Финальный сервис генерации вариантов трассировки,
 * контроля валентности узлов и построения топологии GeoJSON-совместимых
 * объектов.
 */
@Log4j2
@RequiredArgsConstructor
public class NetworkTopologyGenerator {

  private static final GeometryFactory geometryFactory = new GeometryFactory();
  private final static EdgeWeightCalculator angleCalculator = new EdgeWeightCalculator();

  private final Stage2Properties properties;

  /**
   * Генерирует до 3 содержательно отличающихся вариантов прокладки тепловой сети
   * для кластера ОКС.
   */
  @SuppressWarnings("null")
  public List<RoutingResult> generateNetworkVariants(
      int clusterId,
      List<OksObject> cluster,
      DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> graph) {

    log(clusterId, "Generate Network Variants");

    // Оставляем только топ-5 геометрически ближайших камер врезки к потребителям
    // кластера
    OksObject firstOks = cluster.get(0);
    var clusterCenter = firstOks.getConnectionPoint().getCoordinate();

    // Находим все потенциальные стартовые узлы (точки существующей сети) и финишные
    // (ОКС)
    List<VirtualNode> sourceNodes = graph.vertexSet().stream()
        .filter(v -> v.getType() == NodeType.EXISTING_NETWORK_NODE)
        .collect(Collectors.toList());

    List<VirtualNode> targetNodes = graph.vertexSet().stream()
        .filter(v -> v.getType() == NodeType.OKS_CONNECTION)
        .collect(Collectors.toList());

    if (sourceNodes.isEmpty() || targetNodes.isEmpty()) {
      logNoVariants(clusterId);
      if (sourceNodes.isEmpty()) {
        debugTarget(clusterId, targetNodes);
        log(clusterId, "Source Nodes Not Found");
      }
      if (targetNodes.isEmpty()) {
        debugSourceNodes(clusterId, firstOks, sourceNodes);
        log(clusterId, "Target Nodes Not Found");
      }
      var unconnectedOksIds = cluster.stream().map(oks -> oks.getId()).collect(Collectors.toList());
      var summary = VariantSummaryProperties.notConnected(clusterId, unconnectedOksIds);
      return List.of(RoutingResult.empty(summary));
    }
    debugTarget(clusterId, targetNodes);
    debugSourceNodes(clusterId, firstOks, sourceNodes);

    List<VirtualNode> closestSources = sourceNodes.stream()
        .sorted(Comparator.comparingDouble(v -> Distance.distanceSq(v.getCoordinate(), clusterCenter)))
        .limit(properties.getClosesSourcesCount())
        .collect(Collectors.toList());

    // TODO: если generateForSources вернет Empty попробовать взять другие sources
    // IntStream.range(0, )
    debugClosestSources(clusterId, closestSources);

    var variants = generateNetworkVariants(clusterId, cluster, graph, targetNodes, closestSources);
    return variants;
  }

  private List<RoutingResult> generateNetworkVariants(int clusterId, List<OksObject> cluster,
      DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> graph, List<VirtualNode> targetNodes,
      List<VirtualNode> sourceNodes) {
    log(clusterId, "Source Nodes Count: " + sourceNodes.size());
    log(clusterId, "Target Nodes Count: " + targetNodes.size());

    var unconnectedOksIds = cluster.stream().map(oks -> oks.getId()).collect(Collectors.toList());
    var summary = VariantSummaryProperties.notConnected(clusterId, unconnectedOksIds);

    var rawPaths = getRawPaths(graph, cluster, sourceNodes, targetNodes);
    if (rawPaths.isEmpty()) {
      logNoVariants(clusterId);
      log(clusterId, "No Raw Paths Found");
      logMissedNodes(clusterId, sourceNodes, targetNodes);
      return List.of(RoutingResult.empty(summary));
    }
    log(clusterId, "Paths Raw Count: " + rawPaths.size());
    debugRawPaths(clusterId, rawPaths);

    // Динамическая фильтрация и ранжирование путей с учётом геометрии углов
    var evaluatedPaths = getEvaluatedPaths(rawPaths);

    if (evaluatedPaths.isEmpty()) {
      logNoVariants(clusterId);
      log(clusterId, "No Paths Passed Validation");
      logMissedNodes(clusterId, sourceNodes, targetNodes);
      return List.of(RoutingResult.empty(summary));
    }
    log(clusterId, "Paths Passed Validation Count: " + evaluatedPaths.size());
    debugEvaluatedPaths(clusterId, evaluatedPaths);

    // Сортируем варианты по итоговой стоимости (Длина + Штрафы за углы)
    evaluatedPaths.sort(Comparator.comparingDouble(p -> p.totalCost));

    var distinctPaths = filterDistinctVariants(evaluatedPaths);
    if (evaluatedPaths.isEmpty()) {
      logNoVariants(clusterId);
      log(clusterId, "No Found Distinct Paths");
      logMissedNodes(clusterId, sourceNodes, targetNodes);
      return List.of(RoutingResult.empty(summary));
    }
    log(clusterId, "Found Distinct Paths Count: " + distinctPaths.size());
    debugDistinctPaths(clusterId, distinctPaths);

    // Преобразуем выбранные пути в геоинформационные структуры данных
    var finalVariants = getFinalVariants(distinctPaths, clusterId, unconnectedOksIds);

    // Если не удалось проложить ни одного пути, фиксируем кластер как
    // неподключенный
    if (finalVariants.isEmpty()) {
      logNoVariants(clusterId);
      logMissedNodes(clusterId, sourceNodes, targetNodes);
      return List.of();
    }

    log(clusterId, "Found Network Variants Count: " + finalVariants.size());
    return finalVariants;
  }

  private List<GraphPath<VirtualNode, DefaultWeightedEdge>> getRawPaths(
      DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> graph, List<OksObject> cluster,
      List<VirtualNode> sourceNodes, List<VirtualNode> targetNodes) {

    // Используем алгоритм Йена из JGrapht для поиска простых путей без петель
    var pathFinder = new YenKShortestPath<>(graph);

    // Собираем альтернативные пути для всех комбинаций источников и потребителей
    // кластера
    var rawPaths = new ArrayList<GraphPath<VirtualNode, DefaultWeightedEdge>>();
    for (VirtualNode source : sourceNodes) {
      for (VirtualNode target : targetNodes) {
        try {
          // Запрашиваем избыточное количество путей (например, 10), чтобы выбрать 3
          // структурно уникальных
          // int i = counter.incrementAndGet();
          var paths = pathFinder.getPaths(source, target, properties.getChooseFromPathsCount());

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

  private List<RoutingResult> getFinalVariants(List<EvaluatedPath> distinctPaths, int clusterId,
      List<String> unconnectedOksIds) {
    int maxVariants = properties.getMaxVariants();
    var finalVariants = new ArrayList<RoutingResult>();
    for (var variantIdx = 0; variantIdx < distinctPaths.size(); variantIdx++) {
      var evalPath = distinctPaths.get(variantIdx);
      var result = buildTopologicalResult(evalPath.path, clusterId, variantIdx, unconnectedOksIds);
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
   * 
   * @param clusterId
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
        if (overlap > properties.getPathUniquePercent()) {
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
   * 
   * @param clusterIdx
   * @param variantIdx
   * @param unconnectedOksIds
   * 
   */
  private RoutingResult buildTopologicalResult(
      GraphPath<VirtualNode, DefaultWeightedEdge> path, int clusterIdx, int variantIdx,
      List<String> unconnectedOksIds) {

    var id = Id.idForTypeVariantSummary(clusterIdx, variantIdx);
    var variantId = Id.idForTypeVariantSummary(clusterIdx, variantIdx);
    var summary = VariantSummaryProperties.emptyVariant(id, variantId, unconnectedOksIds);

    var vertices = path.getVertexList();

    var pipelines = new ArrayList<Feature<? extends FeatureProperties>>();
    // Карта для подсчета валентности узлов (degree) в рамках выбранного решения
    var nodeValence = new HashMap<Coordinate, Integer>();
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
      if (totalValence > properties.getMaxValence()) {
        log(clusterIdx, "No Topology Built (From " + c1 + ", To " + c2);
        return RoutingResult.empty(summary);
      }

      var line = geometryFactory.createLineString(new Coordinate[] { c1, c2 });
      FeatureProperties properties;
      if (from.hasFeatureProperties()) {
        properties = from.getFeatureProperties();
      } else if (to.hasFeatureProperties()) {
        properties = to.getFeatureProperties();
      } else {
        properties = HeatNetworkProperties.builder().build();
      }
      variantId = Id.idByObjectType(clusterIdx, variantIdx, properties.getObjectType());
      properties.setVariantId(variantId);
      // TODO: debug start
      properties.addAdditionalTextProperty("feature", "pipeline");
      // TODO: debug end

      var fromId = from.getFeatureProperties().getId();
      var toId = to.getFeatureProperties().getId();
      switch (from.getFeatureProperties().getObjectType()) {
        case HEAT_CHAMBER:
        case OKS_CONNECTION_POINT:
          // TODO: debug start
          properties.addAdditionalTextProperty("test-start_node_id", fromId);
          properties.addAdditionalTextProperty("test-end_node_id", toId);
          // TODO: debug end
          break;
        default:
          break;
      }

      pipelines.add(Feature.of(line, properties));
    }

    // Проставление типов узлов согласно ТЗ (Камеры в точках смены атрибутов или
    // ветвления)
    var chambers = new ArrayList<Feature<? extends FeatureProperties>>();
    for (VirtualNode node : vertices) {
      var coordinate = node.getCoordinate();
      var currentValence = nodeValence.getOrDefault(coordinate, 0);
      var point = geometryFactory.createPoint(coordinate);

      Feature<? extends FeatureProperties> feature = null;
      // Если узел является точкой слияния/разветвления (валентность > 2)
      // или это конечная точка ОКС/существующая камера — это объект CHAMBER
      if (node.getType() == NodeType.OKS_CONNECTION) {
        feature = node.getOksObject().getFeature();
      } else if (node.getType() == NodeType.EXISTING_NETWORK_NODE) {
        feature = Feature.of(point, node.getFeatureProperties());
      } else if (currentValence > 2) {
        if (node.hasFeatureProperties()) {
          feature = Feature.of(point, node.getFeatureProperties());
        } else {
          feature = Feature.withDefaultProperties(point);
        }
      } else if (node.getType() == NodeType.VORONOI_SKELETON && currentValence == 2) {
        // По ТЗ: Изломы без смены параметров остаются просто вершинами внутри
        // LineString.
        // Если здесь начинается/заканчивается специальный проход (Шаг 2.4), создается
        // technical_node
        // (логику деления на обычные/специальные участки можно навесить на метаданные
        // ребра).
      }
      if (feature != null) {
        variantId = Id.idByObjectType(clusterIdx, variantIdx, feature.getProperties().getObjectType());
        feature.getProperties().setVariantId(variantId);
        // TODO: debug start
        feature.getProperties().addAdditionalTextProperty("feature", "chamber");
        // TODO: debug end
        chambers.add(feature);
      }
    }

    log(clusterIdx, "Topology Built For Variant: " + variantIdx);
    log(clusterIdx, "Pipelines Count: " + pipelines.size());
    log(clusterIdx, "Chambers Count: " + chambers.size());

    return RoutingResult.of(pipelines, chambers, summary, true);
  }

  private void log(int clusterId, String message) {
    log.info("cluster-" + clusterId + ": " + message);
  }

  private void logMissedNodes(int clusterId, List<VirtualNode> sourceNodes, List<VirtualNode> targetNodes) {
    if (log.isDebugEnabled()) {
      // TODO: debug start
      log(clusterId, "=== Missed Nodes ===");
      log(clusterId, "Source Nodes:");
      logCoordinates(clusterId, sourceNodes);
      log(clusterId, "Target Nodes:");
      logCoordinates(clusterId, targetNodes);
      // TODO: debug end
    }
  }

  private void logCoordinates(int clusterId, List<VirtualNode> nodes) {
    var nodesMessage = nodes.stream()
        .map(v -> formatVirtualNode(v))
        .collect(Collectors.joining(", "));
    log(clusterId, nodesMessage);
  }

  private void debugDistinctPaths(int clusterId, List<EvaluatedPath> distinctPaths) {
    if (log.isTraceEnabled()) {
      // TODO: debug start
      var distPaths = distinctPaths.stream().map(p -> p.path).collect(Collectors.toList());
      DebugUtils.writeGraphPaths(distPaths, clusterId + "-cluster-graph-2-distinct");
      // TODO: debug stop
    }
  }

  private void debugEvaluatedPaths(int clusterId, List<EvaluatedPath> evaluatedPaths) {
    if (log.isTraceEnabled()) {
      // TODO: debug start
      var evalPaths = evaluatedPaths.stream().map(p -> p.path).collect(Collectors.toList());
      DebugUtils.writeGraphPaths(evalPaths, clusterId + "-cluster-graph-1-evaluated");
      // TODO: debug stop
    }
  }

  private void debugRawPaths(int clusterId, List<GraphPath<VirtualNode, DefaultWeightedEdge>> rawPaths) {
    // if (log.isTraceEnabled() && clusterId == 4) {
    if (log.isTraceEnabled()) {
      // TODO: debug start
      DebugUtils.writeGraphPaths(rawPaths, clusterId + "-cluster-graph-0-raw");
      // throw new RuntimeException();
      // TODO: debug stop
    }
  }

  private void debugTarget(int clusterId, List<VirtualNode> target) {
    if (log.isTraceEnabled() && !target.isEmpty()) {
      var coords = target.stream()
          .map(s -> formatVirtualNode(s))
          .collect(Collectors.toList());
      log(clusterId, "Target Coordinates: " + coords);

      var targetF = target.stream()
          .map(n -> {
            var point = geometryFactory.createPoint(n.getCoordinate());
            if (n.hasFeatureProperties()) {
              return Feature.of(point, n.getFeatureProperties());
            }
            return Feature.withDefaultProperties(point);
          });
      DebugUtils.writeFeaturesIn32637(targetF, clusterId + "-cluster-nodes-target");
    }
  }

  private void debugClosestSources(int clusterId, List<VirtualNode> closestSources) {
    if (log.isTraceEnabled()) {
      var closestCoords = closestSources.stream()
          .map(s -> formatVirtualNode(s))
          .collect(Collectors.toList());
      log(clusterId, "Closest Coordinates: " + closestCoords);

      Stream<Feature<? extends FeatureProperties>> closestSourceF = closestSources.stream()
          .map(n -> Feature.of(geometryFactory.createPoint(n.getCoordinate()), n.getFeatureProperties()));
      DebugUtils.writeFeaturesIn32637(closestSourceF, clusterId + "-cluster-nodes-source-closest");
    }
  }

  private void debugSourceNodes(int clusterId, OksObject firstOks, List<VirtualNode> sourceNodes) {
    if (log.isTraceEnabled() && !sourceNodes.isEmpty()) {
      // TODO: debug start
      log(clusterId, "Cluster First Item Id: " + firstOks.getId());
      DebugUtils.writeFeaturesIn32637(Stream.of(firstOks.getFeature()), clusterId + "-cluster-center");

      Stream<Feature<? extends FeatureProperties>> sourceF = sourceNodes.stream()
          .map(n -> Feature.of(geometryFactory.createPoint(n.getCoordinate()), n.getFeatureProperties()));
      DebugUtils.writeFeaturesIn32637(sourceF, clusterId + "-cluster-nodes-source");
      // TODO: debug end
    }
  }

  private void logNoVariants(int clusterId) {
    log(clusterId, "No Network Variants Was Generated");
  }

  private String formatVirtualNode(VirtualNode v) {
    return "[" + v.getCoordinate().x + ", " + v.getCoordinate().y + "] тип: "
        + v.getType();
  }

  @AllArgsConstructor
  private static class EvaluatedPath {
    final GraphPath<VirtualNode, DefaultWeightedEdge> path;
    final double totalCost;
  }
}
