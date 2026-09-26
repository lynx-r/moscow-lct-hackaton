package com.ultra.dit.stage2.service;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ultra.dit.aop.LogExecutionTime;
import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.dto.geojson.properties.VariantSummaryProperties;
import com.ultra.dit.exception.RoutingException;
import com.ultra.dit.stage2.graph.DirectedRoadmapBuilder;
import com.ultra.dit.stage2.model.IndexContainer;
import com.ultra.dit.stage2.model.LocalIndexContainer;
import com.ultra.dit.stage2.model.OksObject;
import com.ultra.dit.stage2.model.RoutingResult;
import com.ultra.dit.stage2.model.VirtualNode;
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
  private final LocalBoundingBoxService boundingBoxService;
  // private final CollisionDetectionService collisionService;
  private final NetworkTopologyGeneratorService topologyGenerator;

  private final int parallelClusterCount;

  public NetworkRoutingService(OksClusteringService clusteringService,
      LocalBoundingBoxService boundingBoxService,
      // CollisionDetectionService collisionService,
      NetworkTopologyGeneratorService topologyGenerator,
      @Value("${app.solution.stage2.parallelClusterCount:3}") int parallelClusterCount) {
    this.clusteringService = clusteringService;
    this.boundingBoxService = boundingBoxService;
    // this.collisionService = collisionService;
    this.topologyGenerator = topologyGenerator;

    this.parallelClusterCount = parallelClusterCount;
  }

  /**
   * Основной пайплайн Этапа 2: выполняет геометрическую трассировку для всех ОКС.
   * 
   * @param globalIndexes Контейнер глобальных пространственных R-Tree индексов
   * @return Список списков вариантов трассировки (до 3 вариантов на каждый
   *         кластер)
   */
  @LogExecutionTime
  public List<List<RoutingResult>> executeRoutingPipeline(IndexContainer globalIndexes) {
    var oksList = globalIndexes.getOksObjects();

    // Шаг 2.0: Автоматическая пространственная кластеризация ОКС (d <= 100м)
    List<List<OksObject>> clusters = clusteringService.clusterOksObjects(oksList);
    log.info("Clusters Count: " + clusters.size());

    try {
      var parallelism = Math.min(parallelClusterCount, clusters.size());
      var customThreadPool = new ForkJoinPool(parallelism);

      var pipelineResults = customThreadPool
          .submit(() -> IntStream.range(0, clusters.size()).parallel().mapToObj(clusterId -> {
            var cluster = clusters.get(clusterId);

            debugCluster(clusterId, cluster);

            // Шаг 2.1: Вычисление Bounding Box кластера и локализация выборки данных
            // (+500м)
            var localEnvelope = boundingBoxService.calculateLocalEnvelope(cluster);
            var localData = boundingBoxService.extractLocalData(localEnvelope, globalIndexes);

            if (localData.isInvalid()) {
              log.info("cluster-" + clusterId + ": Invalid Local Data");
              debugLocalData(clusterId, localData);
              var unconnectedOksIds = cluster.stream().map(oks -> oks.getId()).collect(Collectors.toList());
              var summary = VariantSummaryProperties.notConnected(clusterId, unconnectedOksIds);
              return List.of(RoutingResult.empty(summary));
            }

            debugLocalData(clusterId, localData);

            // Шаг 2.2: Скелетизация Вороного и сборка ориентированного графа (Directed
            // Roadmap)
            var roadmapBuilder = new DirectedRoadmapBuilder(localData, cluster);
            var graph = roadmapBuilder.build();

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

            debugRoadmapGraph(clusterId, graph);

            return topologyGenerator.generateNetworkVariants(clusterId, cluster, graph);
          })).get();
      return pipelineResults.collect(Collectors.toList());
    } catch (InterruptedException e) {
      throw new RoutingException("InterruptedException: Не удалось построить маршруты: " + e.getMessage());
    } catch (ExecutionException e) {
      throw new RoutingException("ExecutionException: Не удалось построить маршруты: " + e.getMessage());
    }
  }

  private void debugRoadmapGraph(int clusterId, DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> graph) {
    if (log.isTraceEnabled()) {
      // TODO: debug start
      DebugUtils.writeGraph(graph, clusterId + "-cluster-graph-roadmap");
      // TODO: debug end
    }
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
      DebugUtils.writeFeaturesIn32637(localData.getNodes().stream(), clusterId + "-cluster-data-nodes");
      DebugUtils.writeFeaturesIn32637(localBoundList.stream(), clusterId + "-cluster-data-execution-envelop");
      // TODO: debug end
    }
  }

  private void debugCluster(int clusterId, List<OksObject> cluster) {
    if (log.isTraceEnabled()) {
      // TODO: debug start
      log.debug("cluster-" + clusterId + ": Size: " + cluster.size());

      Stream<Feature<? extends FeatureProperties>> clusterOks = cluster.stream().map(oks -> oks.getFeature());
      DebugUtils.writeFeaturesIn32637(clusterOks, clusterId + "-cluster-oks-objects");
      // var clusterBuildings = cluster.stream()
      // .map(oks -> Feature.withDefaultProperties(oks.getBuildingPolygon()));
      // DebugUtils.writeFeaturesIn32637(clusterBuildings,
      // "oks-buildings-cluster-" + clusterId);
      // TODO: debug end
    }
  }

}
