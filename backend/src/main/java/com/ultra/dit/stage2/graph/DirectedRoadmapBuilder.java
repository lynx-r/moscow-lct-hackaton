package com.ultra.dit.stage2.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LinearLocation;
import org.locationtech.jts.linearref.LocationIndexedLine;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder;

import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.stage2.model.LocalIndexContainer;
import com.ultra.dit.stage2.model.NodeType;
import com.ultra.dit.stage2.model.OksObject;
import com.ultra.dit.stage2.model.VirtualNode;
import com.ultra.dit.util.DebugUtils;
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

  // 1. Задаем жесткий радиус поиска (в метрах для EPSG:32637)
  // Если магистраль дальше 50 метров, не нужно тянуть линию напролом
  private final double MAX_SNAP_DISTANCE = 50.0;
  private final double MAX_SNAP_DIST_SQ = MAX_SNAP_DISTANCE * MAX_SNAP_DISTANCE;

  // 4. Валидация: проверяем, что получившиеся части имеют физическую длину
  // (защита от микро-сегментов из-за вычислительного шума double)
  private final double MIN_EDGE_LENGTH = 0.01; // 1 сантиметр для EPSG:32637

  // Минимальное число сегментов
  private static int QUADRANT_SEGMENTS = 1;

  private final GeometryFactory geometryFactory = new GeometryFactory();

  // Граф JGrapht, типизированный виртуальными узлами VirtualNode
  private final DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> graph;

  private final STRtree targetOksTree;
  private final STRtree sourceTree;

  private List<Coordinate> sources;
  private List<Coordinate> targets;

  private final double obstacleOffsetMeters;

  public DirectedRoadmapBuilder(LocalIndexContainer localData, List<OksObject> cluster, double obstacleOffsetMeters) {
    this.localData = localData;
    this.cluster = cluster;
    this.obstacleOffsetMeters = obstacleOffsetMeters;

    this.graph = new DirectedWeightedMultigraph<>(DefaultWeightedEdge.class);

    // Собираем все доступные целевые координаты существующих сетей (точки врезки)
    sourceTree = new STRtree();
    localData.getSourceNodes().forEach(node -> {
      var c = node.getGeometry().getCoordinate();
      sourceTree.insert(new Envelope(c), new ExistingSourceNode(c, node.getProperties()));
    });

    // Собираем целевые координаты точек подключения ОКС
    targetOksTree = new STRtree();
    for (OksObject oks : cluster) {
      var c = oks.getConnectionPoint().getCoordinate();
      targetOksTree.insert(new Envelope(c), new TargeOksNode(c, oks));
    }

    sourceTree.build();
    targetOksTree.build();

    sources = localData.getSourceNodesGeometryStream()
        .flatMap(g -> Arrays.stream(g.getCoordinates())).collect(Collectors.toList());
    targets = cluster.stream()
        .map(o -> o.getConnectionPoint().getCoordinate()).collect(Collectors.toList());

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

    // 5. ФИНАЛЬНАЯ ОБРЕЗКА ТУПИКОВЫХ УСОВ
    // (передаем списки оригинальных точек, которые нельзя удалять ни в коем случае)
    pruneDeadEnds();

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
        boolean intersectsBuilding = localData.getConstraintsGeometryStream()
            .anyMatch((g) -> connectionSegment.intersects(g));
        // for (Polygon building : localData.getConstraintsGeometryStream()) {
        // if (connectionSegment.intersects(building)) {
        // intersectsBuilding = true;
        // break;
        // }
        // }

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

    // 2. Проверяем, не попала ли точка на самые концы ребра (с учетом погрешности)
    // Если она совпадает со стартом или концом, разбиение не требуется
    if (loc.isVertex()) {
      Coordinate start = edgeToSplit.getStartPoint().getCoordinate();
      Coordinate end = edgeToSplit.getEndPoint().getCoordinate();
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

  /**
   * Собирает опорные точки (вершины полигонов ограничений) для генерации осей
   * свободных коридоров.
   */
  private List<Coordinate> extractSkeletonSites() {
    Set<Coordinate> sites = new HashSet<>();

    // 1. Вершины локальных пространственных ограничений
    var constraintCoordinates = localData.getConstraintsGeometryStream().flatMap(constraint -> {
      if (constraint == null || constraint.isEmpty()) {
        return Stream.of();
      }

      // Пропускаем собственные полигоны ОКС текущего кластера из общей жесткой
      // блокировки
      if (isOwnClusterBuilding(constraint, cluster)) {
        return Stream.of();
      }

      return processAndAddGeometry(constraint);
    });

    // 2. ДОБАВЛЯЕМ ТОЧКИ СУЩЕСТВУЮЩЕЙ СЕТИ КАК СТИМУЛЫ ДЛЯ ВОРОНОГО
    var networkCoordinates = localData.getNetworksGeometryStream()
        .flatMap(network -> Arrays.stream(network.getCoordinates()));

    // ДОБАВЛЯЕМ ТОЧКИ СУЩЕСТВУЮЩЕЙ ИСТОЧНИКОВ СЕТИ КАК СТИМУЛЫ ДЛЯ ВОРОНОГО
    var sourceNodesCoordinates = sources.stream();

    // 3. ДОБАВЛЯЕМ ТОЧКИ ПОДКЛЮЧЕНИЯ ОКС
    var oksCoordinates = cluster.stream().map(oks -> oks.getConnectionPoint().getCoordinate());

    var executionEnvelop = localData.getExecutionEnvelope();
    // TODO: parallel?
    var coordinates = Stream.of(constraintCoordinates, networkCoordinates, sourceNodesCoordinates, oksCoordinates)
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
    Stream<Feature<? extends FeatureProperties>> geoms = sites.stream().map(c -> geometryFactory.createPoint(c))
        .map(g -> Feature.withDefaultProperties(g));
    // Stream<Feature<? extends FeatureProperties>> features =
    // Stream.of(Feature.withDefaultProperties(geom));
    DebugUtils.writeFeaturesIn32637(geoms, "000???-skeleton-" + localData.hashCode());
    // TODO: debug end

    return new ArrayList<>(sites);
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
    var squareBufferedGeom = convexHull.buffer(obstacleOffsetMeters, QUADRANT_SEGMENTS,
        BufferParameters.JOIN_MITRE);

    // Шаг В: Забираем только внешнюю границу (Boundary) получившейся площадной
    // фигуры
    var boundary = squareBufferedGeom.getBoundary();

    // Добавляем чистые вершины в набор сайтов Вороного
    // for (Coordinate coord : boundary.getCoordinates()) {
    // targetSites.add(coord);
    // }
    return Arrays.stream(boundary.getCoordinates());
  }

  private boolean isOwnClusterBuilding(Geometry constraint, List<OksObject> cluster) {
    if (!(constraint instanceof Polygon) || cluster == null) {
      return false;
    }
    return cluster.stream().anyMatch((oks) -> oks.getBuildingPolygon().equalsExact(constraint));
    // for (OksObject oks : cluster) {
    // if (oks.getBuildingPolygon().equalsExact(constraint)) {
    // return true;
    // }
    // }
    // return false;
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
        if (p1.equals2D(p2))
          continue;

        LineString segment = geometryFactory.createLineString(new Coordinate[] { p1, p2 });

        // Первичный быстрый пре-фильтр пересечений с ограничениями.
        // Строгая валидация с буфером 2.0м будет происходить на Шаге 2.4.
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
      // // Извлекаем внешнюю границу полигона Вороного
      // Coordinate[] coords = polygon.getCoordinates();
      // // ограничения берутся из локального набора данных. не со всей карты. также
      // // диаграмма фильтруется после построения
      // // boolean intersectsConstraint = localData.getConstraintsGeometryStream()
      // // .anyMatch(constraint -> segment.intersects(constraint));
      // // if (!intersectsConstraint) {
      // // validEdges.add(segment);
      // // }

      // for (int j = 0; j < coords.length - 1; j++) {
      // Coordinate from = coords[j];
      // Coordinate to = coords[j + 1];
      // LineString segment = geometryFactory.createLineString(new Coordinate[] {
      // from, to });

      // // Первичный быстрый пре-фильтр пересечений с ограничениями.
      // // Строгая валидация с буфером 2.0м будет происходить на Шаге 2.4.
      // boolean intersectsConstraint = localData.getConstraintsGeometryStream()
      // .anyMatch(constraint -> segment.intersects(constraint));
      // if (!intersectsConstraint) {
      // validEdges.add(segment);
      // }
      // }
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

  private void pruneDeadEnds() {
    // 1. Быстрый поиск: хэшируем координаты целевых точек (камер и источников),
    // чтобы моментально проверять, является ли узел важным терминалом.
    Set<Coordinate> protectedCoordinates = new HashSet<>();
    protectedCoordinates.addAll(sources);
    protectedCoordinates.addAll(targets);

    boolean graphChanged;

    // 2. Запускаем итеративный процесс. Удаление одного тупика может сделать
    // тупиком предыдущую вершину, поэтому чистим до тех пор, пока граф не
    // стабилизируется.
    do {
      graphChanged = false;
      VirtualNode nodeToRemove = null;

      for (VirtualNode vertex : graph.vertexSet()) {
        // Защищенные терминалы (камеры, источники) не трогаем, даже если они
        // тупиковые
        if (protectedCoordinates.contains(vertex.getCoordinate())) {
          continue;
        }

        // Настоящий геометрический тупик имеет всего ОДНО примыкающее ребро
        // (неважно, входящее оно или исходящее)
        int totalConnections = graph.inDegreeOf(vertex) + graph.outDegreeOf(vertex);

        // Также ловим полностью изолированные узлы (0 соединений)
        if (totalConnections <= 1) {
          nodeToRemove = vertex;
          break; // Прерываем итератор, чтобы безопасно удалить узел
        }
      }

      if (nodeToRemove != null) {
        // Удаление вершины автоматически стирает все связанные с ней ребра из графа
        graph.removeVertex(nodeToRemove);
        graphChanged = true;
      }

    } while (graphChanged);

    // --- ФИНАЛЬНЫЙ СУПЕР-КЛИНЕР: УДАЛЕНИЕ ИЗОЛИРОВАННЫХ ЦИКЛОВ И ПЕТЕЛЬ ---

    // 1. Создаем копию графа как ненаправленного для инспектора связности
    // (это позволит найти изолированные треугольники Вороного, даже если они
    // зациклены)
    ConnectivityInspector<VirtualNode, DefaultWeightedEdge> inspector = new ConnectivityInspector<>(graph);
    List<Set<VirtualNode>> connectedComponents = inspector.connectedSets();

    // 2. Хэшируем ключевые терминалы, которые ОБЯЗАНЫ быть в живом графе
    Set<Coordinate> essentialCoordinates = new HashSet<>();
    essentialCoordinates.addAll(sources);
    essentialCoordinates.addAll(targets);

    List<VirtualNode> nodesToClean = new ArrayList<>();

    // 3. Проверяем каждый изолированный подграф
    for (Set<VirtualNode> component : connectedComponents) {
      boolean hasEssentialTerminal = false;

      for (VirtualNode vertex : component) {
        if (essentialCoordinates.contains(vertex.getCoordinate())) {
          hasEssentialTerminal = true;
          break;
        }
      }

      // Если в этом изолированном кусочке/треугольнике нет ни источника, ни камеры
      // подключения,
      // значит вся эта группа ребер — паразитный замкнутый цикл Вороного внутри
      // квартала
      if (!hasEssentialTerminal) {
        nodesToClean.addAll(component);
      }
    }

    // 4. Физически удаляем паразитные полигоны из графа
    for (VirtualNode deadNode : nodesToClean) {
      graph.removeVertex(deadNode);
    }
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
