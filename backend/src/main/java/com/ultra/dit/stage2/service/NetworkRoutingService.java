package com.ultra.dit.stage2.service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.stereotype.Service;

import com.ultra.dit.aop.LogExecutionTime;
import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.dto.geojson.properties.VariantSummaryProperties;
import com.ultra.dit.stage2.config.Stage2Properties;
import com.ultra.dit.stage2.graph.DirectedRoadmapBuilder;
import com.ultra.dit.stage2.model.IndexContainer;
import com.ultra.dit.stage2.model.LocalIndexContainer;
import com.ultra.dit.stage2.model.OksObject;
import com.ultra.dit.stage2.model.RoutingResult;
import com.ultra.dit.util.DebugUtils;

import lombok.extern.log4j.Log4j2;

/**
 * Главный сервис-оркестратор пространственного анализа и трассировки тепловой
 * сети.
 * Координирует выполнение этапов автоматической кластеризации, локализации
 * данных,
 * построения направленного каркаса и генерации итоговых вариантов топологии.
 */
@Log4j2
@Service
public class NetworkRoutingService {

  private final OksClusteringService clusteringService;
  // private final CollisionDetectionService collisionService;

  private final Stage2Properties properties;

  private final ThreadMXBean threadBean;

  public NetworkRoutingService(OksClusteringService clusteringService, Stage2Properties properties) {
    this.clusteringService = clusteringService;
    this.properties = properties;

    threadBean = ManagementFactory.getThreadMXBean();
  }

  /**
   * Основной пайплайн Этапа 2: выполняет геометрическую трассировку для всех ОКС.
   * 
   * @param globalIndexes Контейнер глобальных пространственных R-Tree индексов
   * @return Список списков вариантов трассировки (до 3 вариантов на каждый
   *         кластер)
   */
  @SuppressWarnings("null")
  @LogExecutionTime
  public List<List<RoutingResult>> executeRoutingPipeline(IndexContainer globalIndexes) {
    // Безопасное копирование свойств (snapshot) в текущем HTTP-потоке.
    // Защищает от ScopeNotActiveException при обращении к прокси @RequestScope из
    // ForkJoinPool,
    // так как интерцептор привязывает параметры строго к оригинальному потоку
    // запроса.
    var props = properties.copy();
    var topologyGenerator = new NetworkTopologyGenerator(props);
    var boundingBoxService = new LocalBoundingBoxService(props);

    String sessionId = "Routing-" + UUID.randomUUID().toString().substring(0, 8);

    var oksList = globalIndexes.getOksObjects();

    // Шаг 2.0: Автоматическая пространственная кластеризация ОКС (d <= 100м)
    List<List<OksObject>> clusters = clusteringService.clusterOksObjects(oksList);
    log.info("Clusters Count: " + clusters.size());

    var yamlParallelism = props.getParallelClusterCount();
    var parallelism = (yamlParallelism != null && yamlParallelism > 0)
        ? yamlParallelism
        : Runtime.getRuntime().availableProcessors();

    ForkJoinWorkerThreadFactory poolFactory = pool -> {
      ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
      worker.setName(sessionId + "-worker-" + worker.getPoolIndex());
      return worker;
    };

    var customThreadPool = new ForkJoinPool(parallelism,
        poolFactory,
        null,
        true // asyncMode = true
    );

    // Инициализируем пустой список в памяти
    DebugUtils.startSession(sessionId);

    IntFunction<CompletableFuture<List<RoutingResult>>> findClusterVariants = (int clusterId) -> CompletableFuture
        .supplyAsync(() -> {
          // Внутри вашей параллельной задачи:
          long threadId = Thread.currentThread().getId();
          long startCpu = threadBean.getThreadCpuTime(threadId);
          long startWall = System.nanoTime();

          var cluster = clusters.get(clusterId);

          debugCluster(clusterId, cluster);

          // Шаг 2.1: Вычисление Bounding Box кластера и локализация выборки данных
          // (+500м)
          var localEnvelope = boundingBoxService.calculateLocalEnvelope(cluster);
          var localData = boundingBoxService.extractLocalData(localEnvelope, globalIndexes, cluster, clusterId);

          if (localData.isInvalid()) {
            log.info("cluster-" + clusterId + ": Invalid Local Data");
            debugLocalData(clusterId, localData);
            var unconnectedOksIds = cluster.stream().map(oks -> oks.getId()).collect(Collectors.toList());
            var summary = VariantSummaryProperties.notConnected(clusterId, unconnectedOksIds);
            return List.of(RoutingResult.empty(summary));
          }

          // Шаг 2.2: Скелетизация Вороного и сборка ориентированного графа (Directed
          // Roadmap)
          var roadmapBuilder = new DirectedRoadmapBuilder(localData, props);
          var graph = roadmapBuilder.build();
          debugLocalData(clusterId, localData);

          // debugRoadmapGraph(clusterId, graph);

          // Шаг 2.3 и 2.4: Калькуляция весов ребер с учетом изломов и 2D-буферизации
          // (габарит 2.0м)
          // IntegratedEdgeWeightCalculator weightCalculator = new
          // IntegratedEdgeWeightCalculator(
          // collisionService,
          // localData.getConstraintsGeometry(),
          // cluster);

          // Актуализируем веса ребер в JGrapht на основе штрафных коэффициентов и
          // коллизий
          // for (DefaultWeightedEdge edge : graph.edgeSet()) {
          // VirtualNode from = graph.getEdgeSource(edge);
          // VirtualNode to = graph.getEdgeTarget(edge);

          // double calculatedWeight = weightCalculator.calculateWeight(from, to);
          // graph.setEdgeWeight(edge, calculatedWeight);
          // }

          var result = topologyGenerator.generateNetworkVariants(localData, graph);

          DebugUtils.writeClusterResults(result, clusterId + "");

          long durationCpuMs = (threadBean.getThreadCpuTime(threadId) - startCpu) / 1_000_000;
          long durationWallMs = (System.nanoTime() - startWall) / 1_000_000;

          log.info("cluster-" + clusterId + ": Затрачено CPU времени: " + durationCpuMs + " мс");
          log.info(
              "cluster-" + clusterId + ": Затрачено реального времени: " + durationWallMs + " мс");

          // Если реальное время сильно больше CPU времени — поток где-то простаивал или
          // был заморожен Docker-ом
          if (durationWallMs > durationCpuMs * 1.5) {
            log.warn(
                "cluster-" + clusterId + ": [WARN] Поток %d: Высокое ожидание! CPU: %d мс, Реальное время: %d мс%n",
                threadId, durationCpuMs, durationWallMs);
          }
          return result;
        }, customThreadPool);

    var pipelineResults = IntStream.range(0, clusters.size())
        .mapToObj((clusterId) -> findClusterVariants.apply(clusterId).exceptionally(throwable -> {
          throwable.printStackTrace();
          log.error(throwable);
          return null;
        })).collect(Collectors.toList());
    // Ждем завершения всех задач
    var result = CompletableFuture.allOf(pipelineResults.toArray(new CompletableFuture[0]))
        .thenApply(v -> pipelineResults.stream().map(CompletableFuture::join).filter(Objects::nonNull)
            .collect(Collectors.toList()))
        .join();

    try {
      // Все потоки завершились! Скидываем накопленный массив в файл
      DebugUtils.endSession();
    } catch (IOException e) {
      e.printStackTrace();
    }

    customThreadPool.shutdown();

    return result;

  }

  private void debugLocalData(int clusterId, LocalIndexContainer localData) {
    if (log.isTraceEnabled()) {
      // TODO: debug start
      GeometryFactory geometryFactory = new GeometryFactory();
      var env = localData.getExecutionEnvelope();
      var localBoundPolygon = geometryFactory.toGeometry(env);
      List<Feature<? extends FeatureProperties>> localBoundList = List
          .of(Feature.withDefaultProperties(localBoundPolygon));

      DebugUtils.writeFeaturesIn32637(localData.getConstraints().stream(), clusterId + "-cluster-data-constraints");
      DebugUtils.writeFeaturesIn32637(localData.getNetworks().stream(), clusterId + "-cluster-data-networks");
      DebugUtils.writeFeaturesIn32637(localData.getNodes().stream(), clusterId + "-cluster-data-nodes-all");
      DebugUtils.writeFeaturesIn32637(localData.getSourceNodes().stream(), clusterId + "-cluster-data-nodes-source");
      DebugUtils.writeFeaturesIn32637(localBoundList.stream(), clusterId + "-cluster-data-execution-envelop");
      // TODO: debug end
    }
  }

  private void debugCluster(int clusterId, List<OksObject> cluster) {
    if (log.isTraceEnabled()) {
      // TODO: debug start
      // log.debug("cluster-" + clusterId + ": Size: " + cluster.size());

      // Stream<Feature<? extends FeatureProperties>> clusterOks =
      // cluster.stream().map(oks -> oks.getFeature());
      // DebugUtils.writeFeaturesIn32637(clusterOks, clusterId +
      // "-cluster-oks-objects");
      // var clusterBuildings = cluster.stream()
      // .map(oks -> Feature.withDefaultProperties(oks.getBuildingPolygon()));
      // DebugUtils.writeFeaturesIn32637(clusterBuildings,
      // "oks-buildings-cluster-" + clusterId);
      // TODO: debug end
    }
  }

}
