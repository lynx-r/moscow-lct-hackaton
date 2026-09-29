package com.ultra.dit.stage2.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.YenKShortestPath;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.dto.geojson.properties.HeatNetworkProperties;
import com.ultra.dit.dto.geojson.properties.VariantSummaryProperties;
import com.ultra.dit.stage2.config.Stage2Properties;
import com.ultra.dit.stage2.graph.EdgeWeightCalculator;
import com.ultra.dit.stage2.model.LocalIndexContainer;
import com.ultra.dit.stage2.model.NodeType;
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
      LocalIndexContainer localData,
      DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> graph) {

    var cluster = localData.getCluster();
    var clusterId = localData.getClusterId();
    log(clusterId, "Generate Network Variants");

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
        debugSourceNodes(clusterId, sourceNodes);
        log(clusterId, "Target Nodes Not Found");
      }
      var unconnectedOksIds = cluster.stream().map(oks -> oks.getId()).collect(Collectors.toList());
      var summary = VariantSummaryProperties.notConnected(clusterId, unconnectedOksIds);
      return List.of(RoutingResult.empty(summary));
    }
    debugTarget(clusterId, targetNodes);
    debugSourceNodes(clusterId, sourceNodes);

    var variants = generateNetworkVariants(localData, graph, targetNodes, sourceNodes);
    return variants;
  }

  private List<RoutingResult> generateNetworkVariants(
      LocalIndexContainer localData,
      DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> graph,
      List<VirtualNode> targetNodes,
      List<VirtualNode> sourceNodes) {
    var cluster = localData.getCluster();
    var clusterId = localData.getClusterId();

    log(clusterId, "Source Nodes Count: " + sourceNodes.size());
    log(clusterId, "Target Nodes Count: " + targetNodes.size());

    var unconnectedOksIds = cluster.stream().map(oks -> oks.getId()).collect(Collectors.toList());
    var summary = VariantSummaryProperties.notConnected(clusterId, unconnectedOksIds);

    var rawPaths = getRawPaths(localData, graph, sourceNodes, targetNodes);
    if (rawPaths.isEmpty()) {
      logNoVariants(clusterId);
      log(clusterId, "No Raw Paths Found");
      logMissedNodes(clusterId, sourceNodes, targetNodes);
      return List.of(RoutingResult.empty(summary));
    }
    log(clusterId, "Paths Raw Count: " + rawPaths.size());
    debugRawPaths(clusterId, rawPaths);

    // Динамическая фильтрация и ранжирование путей с учётом геометрии углов
    var evaluatedPaths = getEvaluatedPaths(localData, sourceNodes, rawPaths);

    if (evaluatedPaths.isEmpty()) {
      logNoVariants(clusterId);
      log(clusterId, "No Paths Passed Validation");
      logMissedNodes(clusterId, sourceNodes, targetNodes);
      return List.of(RoutingResult.empty(summary));
    }
    log(clusterId, "Paths Passed Validation Count: " + evaluatedPaths.size());
    debugEvaluatedPaths(clusterId, evaluatedPaths);

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
      LocalIndexContainer localData,
      Graph<VirtualNode, DefaultWeightedEdge> graph,
      List<VirtualNode> sourceNodes, List<VirtualNode> targetNodes) {

    // 1. Находим географический центр вашей рабочей области хакатона (в EPSG:32637)
    var env = localData.getExecutionEnvelope();
    Coordinate centerCoordinate = env.centre(); // Безопасная локальная координата

    // Схлопывание множества источников в один узел
    var superSource = VirtualNode.asSuperSource(centerCoordinate); // Фиктивный узел
    graph.addVertex(superSource);

    for (VirtualNode source : sourceNodes) {
      if (graph.containsVertex(source)) {
        DefaultWeightedEdge e = graph.addEdge(superSource, source);
        if (e != null)
          graph.setEdgeWeight(e, 0.0); // Нулевой вес
      }
    }

    // Используем алгоритм Йена из JGrapht для поиска простых путей без петель
    var pathFinder = new YenKShortestPath<>(graph);

    // Собираем альтернативные пути для всех комбинаций источников и потребителей
    // кластера
    var rawPaths = new ArrayList<GraphPath<VirtualNode, DefaultWeightedEdge>>();
    for (VirtualNode target : targetNodes) {
      if (!graph.containsVertex(target)) {
        continue;
      }

      try {
        // Запрашиваем избыточное количество путей (например, 10), чтобы выбрать 3
        // структурно уникальных
        // int i = counter.incrementAndGet();
        var paths = pathFinder.getPaths(superSource, target, properties.getChooseFromPathsCount());

        if (!paths.isEmpty()) {
          rawPaths.addAll(paths);
        }
      } catch (IllegalArgumentException ignored) {
        // Вызывается JGrapht, если компоненты графа изолированы
      }
    }

    // 5. ОЧИСТКА ТОПОЛОГИИ: Удаляем виртуальный узел из графа перед возвратом
    // путей.
    // JGraphT автоматически сотрет и все добавленные нами нулевые ребра.
    graph.removeVertex(superSource);

    return rawPaths;
  }

  private List<EvaluatedPath> getEvaluatedPaths(
      LocalIndexContainer localData,
      List<VirtualNode> sourceNodes,
      List<GraphPath<VirtualNode, DefaultWeightedEdge>> rawPaths) {
    List<EvaluatedPath> evaluatedPaths = new ArrayList<>();

    // Фиксированные технологические радиусы для EPSG:32637 (в метрах)
    final double SNAP_MAX_DISTANCE = 50.0; // Радиус для поиска своей котельной
    final double DOCKYARD_ZONE_RADIUS = 20.0; // Радиус двора для отключения штрафов углов

    for (var path : rawPaths) {
      List<VirtualNode> vertices = path.getVertexList();
      if (vertices.size() < 2) {
        continue;
      }

      List<VirtualNode> cleanVertices = new ArrayList<>(vertices);

      // DebugUtils.writeVirtualNodes(cleanVertices, localData.getClusterId() +
      // "-0-startVert");

      // Если путь не пустой и самый первый узел — это наш супер-источник
      if (cleanVertices.get(0).getType() == NodeType.SUPER_SOURCE) {
        cleanVertices.remove(0); // Просто удаляем первый элемент O(1)
      }

      if (cleanVertices.isEmpty()) {
        continue;
      }

      // 2. ГАРАНТИЯ СТАРТА: Возвращаем на место желтую точку-источник, если её съел
      // BFS/SuperSource
      var firstNode = cleanVertices.get(0);
      var expectedSource = sourceNodes.stream()
          .filter(s -> s.getCoordinate().distance(firstNode.getCoordinate()) < SNAP_MAX_DISTANCE)
          .findFirst().orElse(null);

      if (expectedSource != null && !cleanVertices.contains(expectedSource)) {
        cleanVertices.add(0, expectedSource);
      }

      // 3. ГАРАНТИЯ ФИНИША: Находим, для какого ОКС строился этот путь.
      // Так как путь Йена заканчивается на ОКС, целевой узел лежит на самом конце
      // оригинального path
      VirtualNode targetNode = path.getEndVertex();

      // Если конечная вершина по какой-то причине не совпадает с целевым ОКС,
      // принудительно восстанавливаем её
      VirtualNode lastNode = cleanVertices.get(cleanVertices.size() - 1);
      if (!lastNode.getCoordinate().equals2D(targetNode.getCoordinate())) {
        if (!cleanVertices.contains(targetNode)) {
          cleanVertices.add(targetNode);
        }
      }

      // DebugUtils.writeVirtualNodes(cleanVertices, localData.getClusterId() +
      // "-0-cleanVertices");

      // 2. ВЫПРЯМЛЯЕМ ПУТЬ: убираем лесенки Вороного с помощью String Pulling
      List<VirtualNode> smoothVertices = pullString(cleanVertices, localData);

      // DebugUtils.writeVirtualNodes(smoothVertices, localData.getClusterId() +
      // "-0-smoothVertices");

      var smoothCoordinates = smoothVertices.stream().map(v -> v.getCoordinate()).collect(Collectors.toList());

      double totalWeightWithPenalties = 0.0;
      boolean validationPassed = true;

      // 3. Рассчитываем НОВУЮ физическую длину выпрямленной трассы в метрах
      // (EPSG:32637)
      double actualLengthMeters = 0.0;
      for (int i = 0; i < smoothCoordinates.size() - 1; i++) {
        actualLengthMeters += smoothCoordinates.get(i).distance(smoothCoordinates.get(i + 1));
      }

      // Накапливаем физическую длину
      totalWeightWithPenalties += actualLengthMeters;

      // Динамический расчёт изломов по цепочке смежных рёбер
      for (int i = 0; i < smoothCoordinates.size() - 2; i++) {
        Coordinate p1 = smoothCoordinates.get(i);
        Coordinate p2 = smoothCoordinates.get(i + 1); // Точка излома
        Coordinate p3 = smoothCoordinates.get(i + 2);

        // Проверяем, находится ли точка излома или целевая точка в пределах радиуса ОКС
        boolean isInsideOksZone = localData.getClusterOksCoordinatesStream()
            .anyMatch(oks -> Distance.isDistanceLessOrEqual(oks, p2, DOCKYARD_ZONE_RADIUS) ||
                Distance.isDistanceLessOrEqual(oks, p3, DOCKYARD_ZONE_RADIUS));

        if (isInsideOksZone) {
          // Разрешаем любые изгибы трубы во дворах у потребителей — пропускаем расчет
          // штрафа
          continue;
        }

        double penalty = angleCalculator.calculateWeight(p1, p2, p3);

        if (penalty == Double.MAX_VALUE) {
          // Путь совершил запрещенный разворот >90 градусов!
          validationPassed = false;
          break;
        }
        totalWeightWithPenalties += penalty;
      }

      if (validationPassed) {
        evaluatedPaths.add(new EvaluatedPath(smoothVertices, totalWeightWithPenalties));
      }
    }

    // Сортируем варианты по итоговой стоимости (Длина + Штрафы за углы)
    evaluatedPaths.sort(Comparator.comparingDouble(p -> p.totalCost));

    return evaluatedPaths;
  }

  private List<VirtualNode> pullString(List<VirtualNode> vertices, LocalIndexContainer localData) {

    // 1. Извлекаем сырые извилистые координаты из пути Йена
    List<Coordinate> rawCoordinates = vertices.stream().map(v -> v.getCoordinate()).collect(Collectors.toList());

    List<VirtualNode> smoothedVertices = new ArrayList<>();
    // Начальная точка (якорь) всегда остается
    smoothedVertices.add(vertices.get(0));

    // double clusterRadius = properties.getClusterSizeMeters();
    double clusterRadius = 50.0;

    int currentAnchorIndex = 0;

    while (currentAnchorIndex < rawCoordinates.size() - 1) {
      Coordinate anchor = rawCoordinates.get(currentAnchorIndex);

      // По умолчанию следующая точка — это просто соседняя точка по графу Вороного
      int furthestVisibleIndex = currentAnchorIndex + 1;

      // Пытаемся заглянуть как можно дальше вперед по цепочке точек
      for (int i = currentAnchorIndex + 2; i < rawCoordinates.size(); i++) {
        Coordinate target = rawCoordinates.get(i);

        // ПРОВЕРКА 1: Входим ли мы в финишную зону ОКС или стартовую зону Источника?
        // Если да — прекращаем оптимизацию лучами, чтобы сохранить точную дворовую
        // геометрию Вороного
        boolean isTerminalZone = localData.getClusterOksCoordinatesStream()
            .anyMatch(oks -> Distance.isDistanceLessOrEqual(oks, target, clusterRadius))
            || localData.getSourceNodesGeometryStream()
                .anyMatch(src -> Distance.isDistanceLessOrEqual(src, target, clusterRadius));

        if (isTerminalZone) {
          // Жесткий стоп выпрямления. Оставляем nextValidIndex как есть (шаг +1) и
          // выходим из for
          break;
        }

        // Строим прямой луч от текущего якоря до удаленной точки
        LineString ray = geometryFactory.createLineString(new Coordinate[] { anchor, target });

        // Быстрая проверка пересечений со зданиями через STRtree
        @SuppressWarnings("unchecked")
        List<Geometry> suspects = localData.getConstraintsTree().query(ray.getEnvelopeInternal());

        boolean isConstrain = suspects.stream().anyMatch((constraint) -> ray.intersects(constraint));

        // Если препятствий на пути луча нет — эта точка нам "видна" напрямую!
        if (!isConstrain) {
          furthestVisibleIndex = i;
        } else {
          // Как только наткнулись на стену — дальше смотреть по прямой нельзя,
          // нужно поворачивать за угол
          break;
        }
      }

      // Фиксируем самую дальнюю видимую точку как новый опорный поворот трассы
      var nextPoint = vertices.get(furthestVisibleIndex);
      smoothedVertices.add(nextPoint);

      // Смещаем наш якорь вперед
      currentAnchorIndex = furthestVisibleIndex;
    }

    // ГАРАНТИЯ СВЯЗНОСТИ: На случай погрешностей double проверяем,
    // что самая последняя точка исходного пути Йена (целевой ОКС) железно
    // присутствует в финале
    var absoluteTarget = vertices.get(vertices.size() - 1);
    var lastAddedNode = smoothedVertices.get(smoothedVertices.size() - 1);

    boolean isLastPointPersist = !lastAddedNode.getCoordinate().equals2D(absoluteTarget.getCoordinate());
    if (isLastPointPersist) {
      // Чтобы не продублировать вершины, проверяем по 2D-координатам, а не по ссылкам
      // объектов!
      boolean alreadyContainsTarget = smoothedVertices.stream()
          .anyMatch(v -> v.getCoordinate().equals2D(absoluteTarget.getCoordinate()));

      if (!alreadyContainsTarget) {
        smoothedVertices.add(absoluteTarget);
      }
    }

    return smoothedVertices;
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
      Set<Coordinate> candidateCoords = candidate.getSmoothCoordinates().stream().collect(Collectors.toSet());

      for (var accepted : distinct) {
        long sharedVertices = accepted.getSmoothCoordinates().stream()
            .filter(v -> candidateCoords.contains(v)).count();

        // Критерий уникальности: если более 70% вершин совпадают, считаем варианты
        // идентичными
        double overlap = (double) sharedVertices
            / Math.max(candidate.smoothVertices.size(), accepted.smoothVertices.size());
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

  private List<RoutingResult> getFinalVariants(List<EvaluatedPath> distinctPaths, int clusterId,
      List<String> unconnectedOksIds) {
    int maxVariants = properties.getMaxVariants();
    var finalVariants = new ArrayList<RoutingResult>();
    for (var variantIdx = 0; variantIdx < distinctPaths.size(); variantIdx++) {
      var evalPath = distinctPaths.get(variantIdx);
      var result = buildTopologicalResult(evalPath, clusterId, variantIdx, unconnectedOksIds);
      if (result.isConnected()) {
        finalVariants.add(result);
        if (finalVariants.size() >= maxVariants) {
          break;
        }
      }
    }

    return finalVariants;
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
      EvaluatedPath evaluatedPath, int clusterIdx, int variantIdx,
      List<String> unconnectedOksIds) {

    var id = Id.idForTypeVariantSummary(clusterIdx, variantIdx);
    var variantId = Id.idForTypeVariantSummary(clusterIdx, variantIdx);

    var vertices = evaluatedPath.smoothVertices;

    var pipelines = new ArrayList<Feature<? extends FeatureProperties>>();
    // Карта для подсчета валентности узлов (degree) в рамках выбранного решения
    var nodeValence = new HashMap<Coordinate, Integer>();
    // Сборка линейных объектов и первичный подсчет степеней вершин
    for (int i = 0; i < vertices.size() - 1; i++) {
      Coordinate c1 = vertices.get(i).getCoordinate();
      Coordinate c2 = vertices.get(i + 1).getCoordinate();

      VirtualNode from = vertices.get(i);
      VirtualNode to = vertices.get(i + 1);

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
        var summaryEmpty = VariantSummaryProperties.emptyVariant(id, variantId, unconnectedOksIds);
        return RoutingResult.empty(summaryEmpty);
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

    var summary = VariantSummaryProperties.builder().id(id).variantId(variantId).unconnectedOksIds(unconnectedOksIds)
        .build();
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
      Stream<Coordinate> distPaths = distinctPaths.stream().flatMap(p -> p.getSmoothCoordinates().stream());
      DebugUtils.writeCoordinates(distPaths, clusterId + "-cluster-graph-2-distinct");
      // TODO: debug stop
    }
  }

  private void debugEvaluatedPaths(int clusterId, List<EvaluatedPath> evaluatedPaths) {
    if (log.isTraceEnabled()) {
      // TODO: debug start
      Stream<Coordinate> evalPaths = evaluatedPaths.stream().flatMap(p -> p.getSmoothCoordinates().stream());
      DebugUtils.writeCoordinates(evalPaths, clusterId + "-cluster-graph-1-evaluated");
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
      DebugUtils.writeFeaturesIn32637(targetF, clusterId + "-cluster-use-nodes-target");
    }
  }

  private void debugSourceNodes(int clusterId, List<VirtualNode> sourceNodes) {
    if (log.isTraceEnabled() && !sourceNodes.isEmpty()) {
      // TODO: debug start
      Stream<Feature<? extends FeatureProperties>> sourceF = sourceNodes.stream()
          .map(n -> Feature.of(geometryFactory.createPoint(n.getCoordinate()), n.getFeatureProperties()));
      DebugUtils.writeFeaturesIn32637(sourceF, clusterId + "-cluster-use-nodes-source");
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
    final List<VirtualNode> smoothVertices;
    final double totalCost;

    List<Coordinate> getSmoothCoordinates() {
      return smoothVertices.stream().map(v -> v.getCoordinate()).collect(Collectors.toList());
    }
  }
}
