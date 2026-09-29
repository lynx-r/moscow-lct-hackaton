package com.ultra.dit.stage2.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.locationtech.jts.densify.Densifier;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LinearLocation;
import org.locationtech.jts.linearref.LocationIndexedLine;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder;

import com.ultra.dit.stage2.config.Stage2Properties;
import com.ultra.dit.stage2.model.LocalIndexContainer;
import com.ultra.dit.stage2.model.LocalIndexContainer.ExistingSourceNode;
import com.ultra.dit.stage2.model.LocalIndexContainer.TargeOksNode;
import com.ultra.dit.stage2.model.NodeType;
import com.ultra.dit.stage2.model.OksObject;
import com.ultra.dit.stage2.model.VirtualNode;
import com.ultra.dit.util.DebugUtils;
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
  private static final double SNAP_EPSILON = 0.05;
  private static final double SNAP_EPSILON_SQ = SNAP_EPSILON * SNAP_EPSILON;

  // 1. Задаем жесткий радиус поиска (в метрах для EPSG:32637)
  // Если магистраль дальше 50 метров, не нужно тянуть линию напролом
  private final double MAX_SNAP_DISTANCE = 50.0;
  private final double MAX_SNAP_DIST_SQ = MAX_SNAP_DISTANCE * MAX_SNAP_DISTANCE;

  // СТИМУЛЯЦИЯ СЕТКИ: Искусственно уплотняем контур препятствия,
  // добавляя точки вдоль каждой стены каждые 10 метров
  private final double distanceTolerance = 12.0; // Шаг дискретизации в метрах

  // Квантуем координаты с шагом 5.0 метров (Схлопываем микро-шум)
  private final double tempGraphGridSize = 5.0;

  // 4. Валидация: проверяем, что получившиеся части имеют физическую длину
  // (защита от микро-сегментов из-за вычислительного шума double)
  private final double MIN_EDGE_LENGTH = 0.01; // 1 сантиметр для EPSG:32637

  // Минимальное число сегментов
  private static int QUADRANT_SEGMENTS = 1;

  // Оптимизированный хэш-индекс для склейки близких вершин
  private final Map<String, VirtualNode> nodeCache = new HashMap<>();

  private final GeometryFactory geometryFactory = new GeometryFactory();

  // Граф JGrapht, типизированный виртуальными узлами VirtualNode
  private final DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> graph;

  private final STRtree targetOksTree;
  private final STRtree sourceTree;
  private final STRtree constraintsTree;

  private final List<Coordinate> sources;
  private final List<Coordinate> targets;

  private final List<Geometry> constraintsWithoutOksBuildings;
  private final List<Coordinate> localConstraintCoordinates;

  private final Stage2Properties properties;

  // 2. Задаем шаг сетки для пустырей.
  // Шаг 40-50 метров идеален: он не перегрузит граф, но создаст полноценные
  // "виртуальные кварталы"
  private final double gridStepMeters = 40.0;

  // Запас безопасности от стен зданий (в метрах для EPSG:32637)
  // Виртуальная точка не должна стоять ближе этого расстояния к реальному дому
  private final double safetyBufferMeters;

  private int clusterId;

  public DirectedRoadmapBuilder(LocalIndexContainer localData, Stage2Properties properties) {
    this.localData = localData;
    this.cluster = localData.getCluster();
    this.clusterId = localData.getClusterId();
    this.properties = properties;

    // Запас безопасности от стен зданий (в метрах для EPSG:32637)
    // Виртуальная точка не должна стоять ближе этого расстояния к реальному дому
    this.safetyBufferMeters = properties.getConstraintOffsetMeters() + 5.0;

    this.graph = new DirectedWeightedMultigraph<>(DefaultWeightedEdge.class);

    // 1. СТРОИМ ЛОКАЛЬНЫЙ ПРОСТРАНСТВЕННЫЙ ИНДЕКС ОГРАНИЧЕНИЙ И ПРЕПЯТСТВИЙ
    constraintsTree = localData.getConstraintsTree();

    // Собираем все доступные целевые координаты существующих сетей (точки врезки)
    sourceTree = localData.getSourceTree();

    // Собираем целевые координаты точек подключения ОКС
    targetOksTree = localData.getTargetOksTree();

    sources = localData.getSourceNodesGeometry();

    targets = cluster.stream()
        .map(o -> o.getConnectionPoint().getCoordinate()).collect(Collectors.toList());

    constraintsWithoutOksBuildings = getConstraintsWithoutOksBuildings().collect(Collectors.toList());

    localConstraintCoordinates = getLocalConstraintCoordinates().collect(Collectors.toList());

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

    // DebugUtils.writeCoordinates(siteCoordinates.stream(), clusterId +
    // "-00-coordinates");

    // 2. Генерация Диаграммы Вороного через JTS
    VoronoiDiagramBuilder voronoiBuilder = new VoronoiDiagramBuilder();
    voronoiBuilder.setSites(siteCoordinates);
    voronoiBuilder.setClipEnvelope(localData.getExecutionEnvelope());
    Geometry voronoiDiagram = voronoiBuilder.getDiagram(geometryFactory);

    // 3. Выделяем безопасные ребра и собираем базовые вершины каркаса
    List<LineString> skeletonEdges = filterAndExtractVoronoiEdges(voronoiDiagram);

    // ПРИНУДИТЕЛЬНОЕ СШИВАНИЕ (SNAP) ТОЧЕК К БЛИЖАЙШИМ ВЕРШИНАМ КАРКАСА
    snap(skeletonEdges);

    // DebugUtils.writeLineStrings(skeletonEdges.stream(), clusterId +
    // "-00-skeleton");

    // СТРОИМ ВРЕМЕННЫЙ НЕОРИЕНТИРОВАННЫЙ ГРАФ ДЛЯ ОЧИСТКИ
    // В неориентированном графе нет понятий outDegree/inDegree, там нет ошибок
    // "Infinity" из-за направлений!
    var tempGraph = new SimpleWeightedGraph<VirtualNode, DefaultWeightedEdge>(
        DefaultWeightedEdge.class);

    // Наполняем tempGraph всеми ребрами из skeletonEdges (просто добавляем вершины
    // и ребра без направления)
    populateUndirectedGraph(tempGraph, skeletonEdges);

    DebugUtils.writeGraph(tempGraph, clusterId + "-cluster-graph-build-1-temp");
    // DebugUtils.writeVirtualNodes(new ArrayList<>(tempGraph.vertexSet()),
    // clusterId + "-temp-9");

    // 6. ОЧИЩАЕМ НЕОРИЕНТИРОВАННЫЙ ГРАФ ОТ УСОВ И РАМОК
    // Передаем туда tempGraph. Он удалит внешнюю рамку и усы, но ОСТАВИТ
    // магистрали,
    // потому что теперь синие аппендиксы удерживают их, как якоря, привязанные к
    // защищенным точкам!
    pruneUndirectedDeadEnds(tempGraph);
    // DebugUtils.writeVirtualNodes(new ArrayList<>(tempGraph.vertexSet()),
    // clusterId + "-prune-9");

    DebugUtils.writeGraph(tempGraph, clusterId + "-cluster-graph-build-2-prune");

    // 7. ПЕРЕНОСИМ ОЧИЩЕННУЮ ТОПОЛОГИЮ В ИТОГОВЫЙ НАПРАВЛЕННЫЙ ГРАФ
    // Теперь, когда в tempGraph остались ТОЛЬКО идеальные чистые трассы и
    // аппендиксы,
    // мы переносим их в DirectedWeightedMultigraph и задаем направления.
    populateFinalOrientedGraph(tempGraph);

    DebugUtils.writeGraph(graph, clusterId + "-cluster-graph-build-3-final");
    // DebugUtils.writeVirtualNodes(new ArrayList<>(graph.vertexSet()), clusterId +
    // "-final-9");

    return graph;
  }

  // ПРИНУДИТЕЛЬНОЕ СШИВАНИЕ (SNAP) ТОЧЕК К БЛИЖАЙШИМ ВЕРШИНАМ КАРКАСА
  private void snap(List<LineString> skeletonEdges) {
    // Объединяем целевые точки в один цикл для оптимизации
    List<Coordinate> pointsToConnect = new ArrayList<>();
    pointsToConnect.addAll(sources);
    pointsToConnect.addAll(targets);

    var toAdd = new ArrayList<LineString>();
    var toRemove = new ArrayList<LineString>();

    for (Coordinate targetPoint : pointsToConnect) {
      LineString closestEdge = null;
      Coordinate closestProjectedPoint = null;
      double minDistSq = Double.MAX_VALUE;

      // Ищем ближайшую точку НА самом ребре (а не только его вершины!)
      for (LineString edge : skeletonEdges) {
        // Чтобы не искать по удаленным ребрам, пропускаем те, чьи копии мы уже пометили
        // на удаление
        if (toRemove.contains(edge)) {
          continue;
        }

        // Исполняем поиск ближайшей точки на отрезке с помощью JTS DistanceOp
        Coordinate[] closestPoints = DistanceOp.nearestPoints(edge, geometryFactory.createPoint(targetPoint));
        Coordinate projected = closestPoints[0]; // Точка на ребре Вороного

        double dSq = Distance.distanceSq(targetPoint, projected);
        if (dSq < minDistSq) {
          minDistSq = dSq;
          closestEdge = edge;
          closestProjectedPoint = projected;
        }
      }

      // Проверяем условия валидности подключения
      if (closestEdge != null && minDistSq <= MAX_SNAP_DIST_SQ) {
        // Создаем потенциальное ребро подключения
        LineString connectionSegment = geometryFactory.createLineString(
            new Coordinate[] { targetPoint, closestProjectedPoint });

        // Проверяем, не пересекает ли этот «аппендикс» здания
        boolean intersectsBuilding = constraintsWithoutOksBuildings.stream()
            .anyMatch((g) -> connectionSegment.intersects(g));

        // Если путь свободен — добавляем ребро
        if (!intersectsBuilding) {
          toAdd.add(connectionSegment);

          // ВНИМАНИЕ: Если точка спроецировалась СЕРЕДИНУ ребра Вороного,
          // для JGraphT это ребро нужно РАЗБИТЬ (Split) на две части в точке
          // closestProjectedPoint,
          // иначе граф не зафиксирует Т-образный перекресток!
          splitEdgeAtPoint(closestEdge, closestProjectedPoint, toAdd, toRemove);
        }
      }
    }

    skeletonEdges.removeAll(toRemove);
    skeletonEdges.addAll(toAdd);
  }

  private void splitEdgeAtPoint(LineString edgeToSplit, Coordinate splitPoint, List<LineString> toAdd,
      List<LineString> toRemove) {
    // 1. Инициализируем индексатор линии для точного разреза
    LocationIndexedLine indexedLine = new LocationIndexedLine(edgeToSplit);
    LinearLocation loc = indexedLine.indexOf(splitPoint);

    Coordinate start = edgeToSplit.getStartPoint().getCoordinate();
    Coordinate end = edgeToSplit.getEndPoint().getCoordinate();

    // Защита от создания микро-петель на концах ребер
    if (Distance.isDistanceLessOrEqual(splitPoint, start, MIN_EDGE_LENGTH)
        || Distance.isDistanceLessOrEqual(splitPoint, end, MIN_EDGE_LENGTH)) {
      return;
    }

    // 2. Проверяем, не попала ли точка на самые концы ребра (с учетом погрешности)
    // Если она совпадает со стартом или концом, разбиение не требуется
    if (loc.isVertex()) {
      if (splitPoint.equals2D(start) || splitPoint.equals2D(end)) {
        return;
      }
    }

    // 3. Разрезаем линию на две части: от начала до точки и от точки до конца
    LineString part1 = (LineString) indexedLine.extractLine(indexedLine.getStartIndex(), loc);
    LineString part2 = (LineString) indexedLine.extractLine(loc, indexedLine.getEndIndex());

    // 4. Валидация: проверяем, что получившиеся части имеют физическую длину
    // (защита от микро-сегментов из-за вычислительного шума double)
    if (part1.getLength() > MIN_EDGE_LENGTH && part2.getLength() > MIN_EDGE_LENGTH) {

      // Удаляем старое длинное ребро, так как оно больше не актуально
      toRemove.remove(edgeToSplit);

      // Добавляем два новых плеча Т-образного перекрестка
      toAdd.add(part1);
      toAdd.add(part2);
    }
  }

  // private void removeIsolatedVoronoiIslands(SimpleWeightedGraph<VirtualNode,
  // DefaultWeightedEdge> tempGraph) {
  // ConnectivityInspector<VirtualNode, DefaultWeightedEdge> inspector = new
  // ConnectivityInspector<>(tempGraph);
  // List<Set<VirtualNode>> components = inspector.connectedSets();

  // // На хакатоне главный признак живой магистрали — её размер.
  // // Изолированный микро-остров на углу дома состоит всего из 3-5 вершин.
  // // Настоящая уличная сеть содержит десятки и сотни вершин.
  // final int MIN_HIGHWAY_NODES = 10;

  // List<VirtualNode> toRemove = new ArrayList<>();
  // for (Set<VirtualNode> component : components) {
  // if (component.size() < MIN_HIGHWAY_NODES) {
  // // Этот кусок графа — изолированная петля Вороного, висящая в воздухе
  // toRemove.addAll(component);
  // }
  // }

  // // Удаляем мусорные микро-острова
  // for (VirtualNode node : toRemove) {
  // tempGraph.removeVertex(node);
  // }
  // }

  /**
   * Собирает опорные точки (вершины полигонов ограничений) для генерации осей
   * свободных коридоров.
   */
  private List<Coordinate> extractSkeletonSites() {
    // Правильный компаратор для TreeSet
    TreeSet<Coordinate> sites = new TreeSet<>(Distance.CoordinateComparator);

    // 1. Вершины локальных пространственных ограничений
    var constraintCoordinates = localConstraintCoordinates.stream();

    // 2. Генерируем мертвые точки для пустырей
    Stream<Coordinate> dummyConstraints = generateDummyConstraintsSites().stream();

    // 2. ДОБАВЛЯЕМ ТОЧКИ СУЩЕСТВУЮЩЕЙ СЕТИ КАК СТИМУЛЫ ДЛЯ ВОРОНОГО
    var networkCoordinates = localData.getNetworksGeometryStream()
        .flatMap(network -> Arrays.stream(network.getCoordinates()));

    // DebugUtils.writeCoordinates(localData.getNetworksGeometryStream()
    // .flatMap(network -> Arrays.stream(network.getCoordinates())), clusterId +
    // "-00-sites-networkCoordinates");

    // ДОБАВЛЯЕМ ТОЧКИ СУЩЕСТВУЮЩЕЙ ИСТОЧНИКОВ СЕТИ КАК СТИМУЛЫ ДЛЯ ВОРОНОГО
    var sourceNodesCoordinates = sources.stream();

    // DebugUtils.writeCoordinates(sources.stream(), clusterId +
    // "-00-sites-sourceNodesCoordinates");

    // 3. ДОБАВЛЯЕМ ТОЧКИ ПОДКЛЮЧЕНИЯ ОКС
    var oksCoordinates = cluster.stream().map(oks -> oks.getConnectionPoint().getCoordinate());

    var executionEnvelop = localData.getExecutionEnvelope();
    // TODO: parallel?
    var coordinates = Stream
        .of(constraintCoordinates, networkCoordinates, sourceNodesCoordinates, oksCoordinates, dummyConstraints)
        .parallel().flatMap(s -> s)
        .filter(c -> executionEnvelop.contains(c))
        .collect(Collectors.toList());

    // DebugUtils.writeCoordinates(coordinates.stream(), clusterId +
    // "-00-sites-all");

    sites.addAll(coordinates);

    // Во избежание пустых диаграмм при отсутствии ограничений, добавим углы
    // Bounding Box
    var geom = geometryFactory.toGeometry(executionEnvelop);
    List<Coordinate> executionEnvelopCoords = Arrays.asList(geom.getCoordinates());
    sites.addAll(executionEnvelopCoords);

    // TODO: debug
    // Stream<Feature<? extends FeatureProperties>> geoms = sites.stream().map(c ->
    // geometryFactory.createPoint(c))
    // .map(g -> Feature.withDefaultProperties(g));
    // Stream<Feature<? extends FeatureProperties>> features =
    // Stream.of(Feature.withDefaultProperties(geom));
    // DebugUtils.writeFeaturesIn32637(geoms, "000???-skeleton-" +
    // localData.hashCode());
    // TODO: debug end

    // DebugUtils.writeCoordinates((sites).stream(), clusterId + "-00-sites");
    return new ArrayList<>(sites);
  }

  private List<Coordinate> generateDummyConstraintsSites() {
    List<Coordinate> dummySites = new ArrayList<>();

    // 1. Берем границы текущей зоны выполнения (Execution Envelope)
    Envelope envelope = localData.getExecutionEnvelope();

    for (double x = envelope.getMinX(); x <= envelope.getMaxX(); x += gridStepMeters) {
      for (double y = envelope.getMinY(); y <= envelope.getMaxY(); y += gridStepMeters) {

        Coordinate candidate = new Coordinate(x, y);

        // Быстрая проверка: попала ли точка внутрь или вплотную к реальному зданию?
        // Строим микро-конверт вокруг точки кандидата для запроса к STRtree
        Envelope searchEnv = new Envelope(
            x - safetyBufferMeters, x + safetyBufferMeters,
            y - safetyBufferMeters, y + safetyBufferMeters);

        @SuppressWarnings("unchecked")
        List<Geometry> suspectConstraints = constraintsTree.query(searchEnv);

        Point candidatePoint = geometryFactory.createPoint(candidate);

        boolean isInsideOrTooClose = suspectConstraints.stream().anyMatch((building) -> {
          // Если точка внутри здания или слишком близко к его фасаду — отбрасываем её
          return (candidatePoint.within(building) || candidatePoint.distance(building) < safetyBufferMeters);
        });

        // Если точка упала на чистый пустырь или широкую площадь — фиксируем её как
        // новый "глухой сайт"
        if (!isInsideOrTooClose) {
          dummySites.add(candidate);
        }
      }
    }

    return dummySites;
  }

  /**
   * Вершины локальных пространственных ограничений
   * 
   * @return
   */
  private Stream<Coordinate> getLocalConstraintCoordinates() {
    // 1. Вершины локальных пространственных ограничений
    return localData.getConstraintsGeometryStream().flatMap(constraint -> {
      if (isOwnClusterBuilding(constraint, cluster)) {
        // ОСОБАЯ ЛОГИКА ДЛЯ ЦЕЛЕВОГО ЗДАНparentИЯ ОКС:
        // Вместо расширения наружу, мы делаем ОКОННЫЙ или ВНУТРЕННИЙ буфер,
        // чтобы освободить место для заезда трассы Вороного внутрь двора/фасада
        return processTargetOksBuildingGeometry(constraint);
      }
      // Стандартный конвейер для всех остальных зданий-препятствий
      return processAndAddGeometry(constraint);
    });
  }

  /**
   * Конвейер оптимизации: Выпуклая оболочка -> Квадратный буфер -> Извлечение
   * чистых координат.
   * 
   * @return
   */
  private Stream<Coordinate> processAndAddGeometry(Geometry geom) {
    // Шаг А: Схлопываем сложную фасадную геометрию в выпуклый контур (Convex Hull)
    var convexHull = geom.convexHull();

    // Шаг Б: Строим вокруг него параллельный квадратный контур отступа
    // Квадратные стыки углов без дуг BufferParameters.JOIN_MITRE
    var squareBufferedGeom = convexHull.buffer(properties.getConstraintOffsetMeters(), QUADRANT_SEGMENTS,
        BufferParameters.JOIN_MITRE);

    Geometry densifiedGeom = Densifier.densify(squareBufferedGeom, distanceTolerance);

    var boundary = densifiedGeom.getBoundary();
    // Добавляем чистые вершины в набор сайтов Вороного
    var densifiedCoordinates = Arrays.stream(boundary.getCoordinates());

    // фильтрация точек, которые находятся ближе tempGraphGridSize 3-4 метров друг к
    // другу.
    var filteredPoints = new ArrayList<Coordinate>();
    densifiedCoordinates.forEach(current -> {
      var isTooClose = filteredPoints.stream()
          .anyMatch(saved -> Distance.isDistanceLessOrEqual(current, saved, tempGraphGridSize));
      if (!isTooClose) {
        filteredPoints.add(current);
      }
    });

    return filteredPoints.stream();
  }

  /**
   * Конвейер оптимизации для целевого здания ОКС:
   * Выпуклая оболочка -> Уменьшенный/Специальный буфер -> Извлечение редких
   * координат.
   */
  private Stream<Coordinate> processTargetOksBuildingGeometry(Geometry geom) {
    // Шаг А: Схлопываем сложную фасадную геометрию в выпуклый контур (Convex Hull)
    var convexHull = geom.convexHull();

    // Шаг Б: Строим вокруг него буфер отступа на базе
    // oksConnectionPointBufferMeters
    // Обычно этот буфер намного меньше стандартного obstacleOffsetMeters (например,
    // 2.0-3.0 метра вместо 15.0),
    // что освобождает "карман" улицы перед фасадом для заезда фиолетовой линии
    // Вороного.
    double targetOffset = properties.getOksConnectionPointBufferMeters();
    var squareBufferedGeom = convexHull.buffer(targetOffset, QUADRANT_SEGMENTS,
        BufferParameters.JOIN_MITRE);

    // Шаг В: Забираем только внешнюю границу получившейся площадной фигуры
    var boundary = squareBufferedGeom.getBoundary();

    // Шаг Г: СТИМУЛЯЦИЯ СЕТКИ, но с БОЛЬШИМ шагом (например, 15 или 20 метров).
    // Это критически важно: если нарезать точки на вводе слишком часто, Вороной
    // построит частокол.
    // Редкая нарезка (15.0 метров) заставит диаграмму сделать ОДИН красивый,
    // плавный въезд к ОКС.
    double rareDistanceTolerance = 15.0;
    Geometry densifiedGeom = org.locationtech.jts.densify.Densifier.densify(boundary, rareDistanceTolerance);

    // Добавляем чистые вершины в набор сайтов Вороного
    return Arrays.stream(densifiedGeom.getCoordinates());
  }
  /**
   * Специальная обработка целевого здания ОКС.
   * Создает коридор безопасности для заезда сети непосредственно к точке врезки.
   */
  // private Stream<Coordinate> processTargetOksBuilding(Geometry buildingGeom) {
  // var convexHull = buildingGeom.convexHull();

  // // ИСПОЛЬЗУЕМ oksConnectionPointBufferMeters!
  // // Вместо жесткого JOIN_MITRE наружу, мы делаем буфер с ОТРИЦАТЕЛЬНЫМ или
  // // МИНИМАЛЬНЫМ сдвигом,
  // // либо оставляем контур как есть, но вырезаем из него окрестность точки
  // // подключения

  // var customBufferedGeom = convexHull.buffer(oksConnectionPointBufferMeters,
  // QUADRANT_SEGMENTS,
  // BufferParameters.JOIN_MITRE);

  // // Уплотняем более редко (шаг 15-20 метров), чтобы не создавать частокол на
  // // входе
  // Geometry densifiedGeom = Densifier.densify(customBufferedGeom, 15.0);

  // var boundary = densifiedGeom.getBoundary();
  // return Arrays.stream(boundary.getCoordinates());
  // }

  /**
   * Список локальных ограничений без учета зданий кластера
   * 
   * @return
   */
  private Stream<Geometry> getConstraintsWithoutOksBuildings() {
    // 1. Вершины локальных пространственных ограничений
    return localData.getConstraintsGeometryStream().filter(constraint -> {
      if (constraint == null || constraint.isEmpty()) {
        return false;
      }

      // Пропускаем собственные полигоны ОКС текущего кластера из общей жесткой
      // блокировки
      if (isOwnClusterBuilding(constraint, cluster)) {
        return false;
      }

      return true;
    });
  }

  private boolean isOwnClusterBuilding(Geometry constraint, List<OksObject> cluster) {
    return cluster.stream()
        .map(oks -> oks.getBuildingPolygon()) // Метод получения полигона здания ОКС
        .filter(Objects::nonNull)
        .anyMatch((targetBldg) -> targetBldg.intersects(constraint) || targetBldg.equalsTopo(constraint));
  }

  /**
   * Преобразует полигоны Вороного в набор уникальных линейных сегментов (осей
   * коридоров), отбрасывая те, которые изначально пересекают зоны запрета.
   */
  private List<LineString> filterAndExtractVoronoiEdges(Geometry voronoiDiagram) {
    List<LineString> validEdges = new ArrayList<>();

    for (int i = 0; i < voronoiDiagram.getNumGeometries(); i++) {
      Geometry cell = voronoiDiagram.getGeometryN(i);
      if (!(cell instanceof Polygon)) {
        continue;
      }

      LineString exteriorRing = ((Polygon) cell).getExteriorRing();

      // Разбиваем кольцо полигона на отдельные отрезки (сегменты из 2 точек)
      for (int j = 0; j < exteriorRing.getNumPoints() - 1; j++) {
        Coordinate p1 = exteriorRing.getCoordinateN(j);
        Coordinate p2 = exteriorRing.getCoordinateN(j + 1);

        // Пропускаем вырожденные отрезки-точки
        if (p1.equals2D(p2)) {
          continue;
        }

        LineString segment = geometryFactory.createLineString(new Coordinate[] { p1, p2 });

        // Первичный быстрый пре-фильтр пересечений с ограничениями.
        boolean intersectsConstraint = localData.getConstraintsGeometryStream()
            .anyMatch(constraint -> segment.intersects(constraint));
        if (!intersectsConstraint) {
          // TODO: Примечание: Если ребер очень много, линейный поиск .stream().anyMatch
          // может
          // замедлить код. В таком случае отрезки дедуплицируют, нормализуя координаты
          // (чтобы X1 всегда был меньше X2) и складывая их в Set<Pair<Coordinate,
          // Coordinate>>.
          // Проверяем, нет ли уже такого ребра в списке (в любую сторону)
          boolean isDuplicate = validEdges.stream()
              .anyMatch(existing -> (existing.getStartPoint().getCoordinate().equals2D(p1)
                  && existing.getEndPoint().getCoordinate().equals2D(p2)) ||
                  (existing.getStartPoint().getCoordinate().equals2D(p2)
                      && existing.getEndPoint().getCoordinate().equals2D(p1)));

          if (!isDuplicate) {
            validEdges.add(segment);
          }
        }

      }

    }
    return validEdges;
  }

  /**
   * Строит топологические связи в JGrapht, направляя ребра строго ОТ источников К
   * потребителям.
   */
  private void populateUndirectedGraph(Graph<VirtualNode, DefaultWeightedEdge> graph, List<LineString> skeletonEdges) {
    // Для каждого отрезка безопасного каркаса генерируем ориентированные ребра
    for (LineString edge : skeletonEdges) {
      Coordinate c1 = edge.getStartPoint().getCoordinate();
      Coordinate c2 = edge.getEndPoint().getCoordinate();

      VirtualNode node1_in = createVirtualNode(c1);
      VirtualNode node2_out = createVirtualNode(c2);

      boolean isLoop = node1_in.equals(node2_out);
      if (graph.containsEdge(node1_in, node2_out) || isLoop) {
        continue;
      }

      graph.addVertex(node1_in);
      graph.addVertex(node2_out);

      var newEdge = graph.addEdge(node1_in, node2_out);
      if (edge != null) {
        // Инициализируем базовым весом (физическая длина)
        graph.setEdgeWeight(newEdge, edge.getLength());
      }

    }
  }

  private VirtualNode createVirtualNode(Coordinate c) {
    long gridX = Math.round(c.x / tempGraphGridSize);
    long gridY = Math.round(c.y / tempGraphGridSize);

    // Создаем уникальный текстовый ключ для этой ячейки сетки
    String gridKey = gridX + "_" + gridY;

    // Если в этой ячейке 5х5 метров уже есть вершина — переиспользуем её!
    if (nodeCache.containsKey(gridKey)) {
      return nodeCache.get(gridKey);
    }

    // Если ячейка пустая, создаем новую вершину, но привязываем её
    // к ОВЫРАВНЕННЫМ координатам центра ячейки (или к оригинальной координате)
    Coordinate snappedCoord = new Coordinate(gridX * tempGraphGridSize, gridY * tempGraphGridSize);

    // Ищем кандидатов среди sources
    List<ExistingSourceNode> sourceCandidates = getSearchCandidates(c, sourceTree);
    for (var source : sourceCandidates) {
      if (Distance.isDistanceLessOrEqual(c, source.getCoordinate(), SNAP_EPSILON_SQ)) {
        // привязка к выровненным координатам центра ячейки (или к оригинальной
        // координате)
        return VirtualNode.withFeatureProperties(source.getCoordinate(), NodeType.EXISTING_NETWORK_NODE,
            source.getProperties());
      }
    }

    // Ищем кандидатов среди targets
    List<TargeOksNode> targetCandidates = getSearchCandidates(c, targetOksTree);
    for (var target : targetCandidates) {
      if (Distance.isDistanceLessOrEqual(c, target.getCoordinate(), SNAP_EPSILON_SQ)) {
        // привязка к выровненным координатам центра ячейки (или к оригинальной
        // координате)
        return VirtualNode.withOksObject(target.getCoordinate(), NodeType.OKS_CONNECTION, target.getOks());
      }
    }

    // привязка к выровненным координатам центра ячейки (или к оригинальной
    // координате)
    return VirtualNode.asVoronoiSkeleton(snappedCoord);
  }

  private void pruneUndirectedDeadEnds(Graph<VirtualNode, DefaultWeightedEdge> graph) {
    // 1. Быстрый поиск: хэшируем координаты целевых точек (камер и источников),
    // чтобы моментально проверять, является ли узел важным терминалом.
    TreeSet<Coordinate> protectedCoordinates = new TreeSet<>(Distance.CoordinateComparator);
    protectedCoordinates.addAll(sources);
    protectedCoordinates.addAll(targets);

    // Перед чисткой помечаем все узлы каркаса, куда воткнулись синие аппендиксы,
    // как ЗАЩИЩЕННЫЕ
    Set<VirtualNode> anchorNodes = new HashSet<>();
    for (DefaultWeightedEdge edge : graph.edgeSet()) {
      VirtualNode src = graph.getEdgeSource(edge);
      VirtualNode trg = graph.getEdgeTarget(edge);

      // Если один из узлов — защищенный источник/цель, то второй узел (перекресток)
      // тоже защищаем!
      if (src.getType() == NodeType.EXISTING_NETWORK_NODE || src.getType() == NodeType.OKS_CONNECTION) {
        anchorNodes.add(trg);
      }
      if (trg.getType() == NodeType.EXISTING_NETWORK_NODE || trg.getType() == NodeType.OKS_CONNECTION) {
        anchorNodes.add(src);
      }
    }

    boolean graphChanged;

    var l = new ArrayList<VirtualNode>();

    // 2. Запускаем итеративный процесс. Удаление одного тупика может сделать
    // тупиком предыдущую вершину, поэтому чистим до тех пор, пока граф не
    // стабилизируется.
    do {
      graphChanged = false;
      VirtualNode nodeToRemove = null;

      for (VirtualNode vertex : graph.vertexSet()) {
        // Защищенные камеры и ОКС удалять нельзя!
        boolean isProtectedVertex = vertex.getType() == NodeType.EXISTING_NETWORK_NODE
            || vertex.getType() == NodeType.OKS_CONNECTION || protectedCoordinates.contains(vertex.getCoordinate());
        if (isProtectedVertex) {
          continue;
        }

        if (anchorNodes.contains(vertex)) {
          continue; // Запрещаем удалять точки стыковки аппендиксов с магистралями!
        }
        // В неориентированном графе у истинного тупика (край рамки или пустой ус)
        // количество смежных ребер (степень узла) <= 1
        if (graph.degreeOf(vertex) <= 1) {
          nodeToRemove = vertex;
          l.add(vertex);
          break;
        }
      }

      if (nodeToRemove != null) {
        // Удаление вершины автоматически стирает все связанные с ней ребра из графа
        graph.removeVertex(nodeToRemove);
        graphChanged = true;
      }

    } while (graphChanged);
  }

  private void populateFinalOrientedGraph(SimpleWeightedGraph<VirtualNode, DefaultWeightedEdge> tempGraph) {
    // 2. Сначала переносим ВСЕ вершины из временного графа в финальный
    for (VirtualNode vertex : tempGraph.vertexSet()) {
      graph.addVertex(vertex);
    }

    // Очередь для волнового обхода (BFS) и Set для отслеживания посещенных вершин
    Queue<VirtualNode> queue = new LinkedList<>();
    Set<VirtualNode> visitedNodes = new HashSet<>();

    // 3. Находим все стартовые вершины (источники) в tempGraph и помещаем их в
    // очередь
    for (VirtualNode vertex : tempGraph.vertexSet()) {
      if (vertex.getType() == NodeType.EXISTING_NETWORK_NODE || sources.contains(vertex.getCoordinate())) {
        queue.add(vertex);
        visitedNodes.add(vertex);
      }
    }

    // 4. Основной волновой алгоритм трассировки направлений
    while (!queue.isEmpty()) {
      VirtualNode currentNode = queue.poll();

      // Находим все смежные ребра в неориентированном графе
      Set<DefaultWeightedEdge> edges = tempGraph.edgesOf(currentNode);

      for (DefaultWeightedEdge edge : edges) {
        VirtualNode sourceCandidate = tempGraph.getEdgeSource(edge);
        VirtualNode targetCandidate = tempGraph.getEdgeTarget(edge);

        // Определяем, какой из узлов является следующим шагом "вниз по течению"
        VirtualNode nextNode = (sourceCandidate.equals(currentNode)) ? targetCandidate : sourceCandidate;

        // Если узел еще не был посещен нашей волной направления
        if (!visitedNodes.contains(nextNode)) {
          addToDirectGraph(tempGraph, currentNode, edge, nextNode);

          visitedNodes.add(nextNode);
          queue.add(nextNode);
        }
        // Если узел уже посещен (альтернативный путь/петля), но ребра между ними в
        // направленном графе еще нет
        else if (!graph.containsEdge(currentNode, nextNode) && !graph.containsEdge(nextNode, currentNode)) {
          addToDirectGraph(tempGraph, currentNode, edge, nextNode);
        }
      }
    }

    // 5. КРИТИЧЕСКИЙ ФОЛБЕК ДЛЯ АППЕНДИКСОВ ОКС
    // Если ОКС стоял слишком изолированно и волна BFS от ТЭЦ до него не дошла через
    // фиолетовый каркас,
    // принудительно восстанавливаем его ребро snap в правильном направлении
    for (VirtualNode vertex : tempGraph.vertexSet()) {
      if (vertex.getType() == NodeType.OKS_CONNECTION || targets.contains(vertex.getCoordinate())) {
        // Если к этому ОКС в финальном графе так и не пришло ни одного входящего ребра
        if (graph.inDegreeOf(vertex) == 0) {
          // Находим, с кем он был соединен в tempGraph
          Set<DefaultWeightedEdge> connections = tempGraph.edgesOf(vertex);
          for (DefaultWeightedEdge edge : connections) {
            VirtualNode sourceCandidate = tempGraph.getEdgeSource(edge);
            VirtualNode targetCandidate = tempGraph.getEdgeTarget(edge);

            // Определяем, какой из узлов является следующим шагом "вниз по течению"
            VirtualNode networkNode = (sourceCandidate.equals(vertex)) ? targetCandidate : sourceCandidate;

            // Добавляем ребро строго НАПРАВЛЕННОЕ В СТОРОНУ ОКС: networkNode -> ОКС
            if (!graph.containsEdge(networkNode, vertex)) {
              addToDirectGraph(tempGraph, networkNode, edge, vertex);
            }
          }
        }
      }
    }
  }

  private void addToDirectGraph(Graph<VirtualNode, DefaultWeightedEdge> tempGraph,
      VirtualNode currentNode,
      DefaultWeightedEdge edge, VirtualNode nextNode) {
    // Направление строго по ходу движения волны: от текущего узла к следующему
    double weight = tempGraph.getEdgeWeight(edge);

    DefaultWeightedEdge directedEdge = graph.addEdge(currentNode, nextNode);
    if (directedEdge != null) {
      graph.setEdgeWeight(directedEdge, weight);
    }
  }

  @SuppressWarnings("unchecked")
  private <R> List<R> getSearchCandidates(Coordinate c, STRtree searchTree) {
    var searchEnv = new Envelope(c.x - SNAP_EPSILON, c.x + SNAP_EPSILON, c.y - SNAP_EPSILON, c.y + SNAP_EPSILON);
    return searchTree.query(searchEnv);
  }
}
