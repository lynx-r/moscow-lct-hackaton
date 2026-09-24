package com.ultra.dit.stage2.service;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.stereotype.Service;

import com.ultra.dit.dto.Feature;
import com.ultra.dit.exception.RoutingException;
import com.ultra.dit.service.DebugUtils;
import com.ultra.dit.stage2.graph.DirectedRoadmapBuilder;
import com.ultra.dit.stage2.model.IndexContainer;
import com.ultra.dit.stage2.model.LocalIndexContainer;
import com.ultra.dit.stage2.model.OksObject;
import com.ultra.dit.stage2.model.RoutingResult;
import com.ultra.dit.stage2.model.VirtualNode;

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

  public NetworkRoutingService(OksClusteringService clusteringService,
      LocalBoundingBoxService boundingBoxService,
      // CollisionDetectionService collisionService,
      NetworkTopologyGeneratorService topologyGenerator) {
    this.clusteringService = clusteringService;
    this.boundingBoxService = boundingBoxService;
    // this.collisionService = collisionService;
    this.topologyGenerator = topologyGenerator;
  }

  /**
   * Основной пайплайн Этапа 2: выполняет геометрическую трассировку для всех ОКС.
   * 
   * @param globalIndexes Контейнер глобальных пространственных R-Tree индексов
   * @return Список списков вариантов трассировки (до 3 вариантов на каждый
   *         кластер)
   */
  public List<List<RoutingResult>> executeRoutingPipeline(IndexContainer globalIndexes) {
    log.info("NetworkRoutingService::executeRoutingPipeline start");

    var oksList = globalIndexes.getOksObjects();

    // Шаг 2.0: Автоматическая пространственная кластеризация ОКС (d <= 100м)
    List<List<OksObject>> clusters = clusteringService.clusterOksObjects(oksList);
    log.info("executeRoutingPipeline clusters.size() = " + clusters.size());

    AtomicInteger clusterIndex = new AtomicInteger(0);
    ForkJoinPool customThreadPool = new ForkJoinPool(3); // Ограничиваемся 3 параллельными кластерами

    try {
      var pipelineResults = customThreadPool.submit(() -> clusters.parallelStream().map(cluster -> {
        int clusterIdx = clusterIndex.incrementAndGet();
        // // TODO: debug start
        // var clusterFeatures = cluster.stream().flatMap(oks -> {
        // return List.of(Feature.withDefaultProperties(oks.getConnectionPoint()),
        // Feature.withDefaultProperties(oks.getBuildingPolygon())).stream();
        // });
        // DebugUtils.transformAndWriteFeatureCollectionToFile(clusterFeatures,
        // "0-" + clusterIdx + "-clusterFeatureCollection.geojson");
        // // TODO: debug end

        // Шаг 2.1: Вычисление Bounding Box кластера и локализация выборки данных
        // (+500м)
        Envelope localEnvelope = boundingBoxService.calculateLocalEnvelope(cluster);
        LocalIndexContainer localData = boundingBoxService.extractLocalData(localEnvelope, globalIndexes);

        // Шаг 2.2: Скелетизация Вороного и сборка ориентированного графа (Directed
        // Roadmap)
        log.info("DirectedRoadmapBuilder::build cluster-" + clusterIdx + " start");
        DirectedRoadmapBuilder roadmapBuilder = new DirectedRoadmapBuilder(localData, cluster);
        DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> graph = roadmapBuilder.build();
        log.info("DirectedRoadmapBuilder::build cluster-" + clusterIdx + " finish");

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

        if (log.isDebugEnabled()) {
          // TODO: debug start
          GeometryFactory geometryFactory = new GeometryFactory();
          var env = localData.getExecutionEnvelope();
          var envCoordinates = new Coordinate[] {
              new Coordinate(env.getMinX(), env.getMinY()), new Coordinate(env.getMinX(), env.getMaxY()),
              new Coordinate(env.getMaxX(), env.getMaxY()), new Coordinate(env.getMaxX(), env.getMinY()),
              new Coordinate(env.getMinX(), env.getMinY())

          };
          var localBoundPolygon = geometryFactory.createPolygon(envCoordinates);
          List<Feature> localBoundList = List.of(Feature.withDefaultProperties(localBoundPolygon));
          var localFeatures = List.of(localData.getConstraints(),
              localData.getNetworks(),
              localData.getNodes(),
              localBoundList)
              .stream().flatMap(l -> l.stream());

          DebugUtils.writeFeaturesIn32637(localFeatures, "1-" + clusterIdx + "-localFeatures");

          DebugUtils.writeGraph(graph, "graph-" + clusterIdx);

          // TODO: debug end
        }

        log.info("NetworkTopologyGeneratorService::generateNetworkVariants for cluster-" + clusterIdx + " start");
        List<RoutingResult> clusterVariants = topologyGenerator.generateNetworkVariants(cluster, graph);
        log.info("NetworkTopologyGeneratorService::generateNetworkVariants for cluster-" + clusterIdx + " finish");
        return clusterVariants;
      }).filter(variant -> !variant.isEmpty()).collect(Collectors.toList())).get();
      return pipelineResults;
    } catch (InterruptedException e) {
      throw new RoutingException("Не удалось построить маршруты: " + e.getMessage());
    } catch (ExecutionException e) {
      throw new RoutingException("Не удалось построить маршруты: " + e.getMessage());
    } finally {
      log.info("NetworkRoutingService::executeRoutingPipeline finish");
    }

  }

}
