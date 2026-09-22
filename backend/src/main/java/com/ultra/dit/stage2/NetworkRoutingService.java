package com.ultra.dit.stage2;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.locationtech.jts.geom.Envelope;
import org.springframework.stereotype.Service;

import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.FeatureCollection;
import com.ultra.dit.service.DebugUtils;
import com.ultra.dit.stage2.graph.DirectedRoadmapBuilder;
import com.ultra.dit.stage2.graph.IntegratedEdgeWeightCalculator;
import com.ultra.dit.stage2.graph.NetworkTopologyGenerator;
import com.ultra.dit.stage2.model.IndexContainer;
import com.ultra.dit.stage2.model.LocalIndexContainer;
import com.ultra.dit.stage2.model.OksObject;
import com.ultra.dit.stage2.model.RoutingResult;
import com.ultra.dit.stage2.model.VirtualNode;
import com.ultra.dit.stage2_test.FeatureMapper;

/**
 * Главный сервис-оркестратор пространственного анализа и трассировки тепловой
 * сети.
 * Координирует выполнение этапов автоматической кластеризации, локализации
 * данных,
 * построения направленного каркаса и генерации итоговых вариантов топологии.
 */
@Service
public class NetworkRoutingService {

  private final OksClusteringService clusteringService;
  private final LocalBoundingBoxService boundingBoxService;
  private final CollisionDetectionService collisionService;
  private final NetworkTopologyGenerator topologyGenerator;

  public NetworkRoutingService(OksClusteringService clusteringService,
      LocalBoundingBoxService boundingBoxService,
      CollisionDetectionService collisionService,
      NetworkTopologyGenerator topologyGenerator) {
    this.clusteringService = clusteringService;
    this.boundingBoxService = boundingBoxService;
    this.collisionService = collisionService;
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
    List<List<RoutingResult>> pipelineResults = new ArrayList<>();

    var oksList = globalIndexes.getOksObjects();

    // Шаг 2.0: Автоматическая пространственная кластеризация ОКС (d <= 100м)
    List<List<OksObject>> clusters = clusteringService.clusterOksObjects(oksList);

    System.out.println("SIZE: " + clusters.size());

    for (List<OksObject> cluster : clusters) {
      // TODO: debug start OK
      var clusterFeatures = cluster.stream().flatMap(oks -> {
        return List.of(Feature.withDefaultProperties(oks.getConnectionPoint()),
            Feature.withDefaultProperties(oks.getBuildingPolygon())).stream();
      }).collect(Collectors.toList());
      var clusterFeatureCollection = FeatureCollection.of(clusterFeatures);
      DebugUtils.writeFeatureCollectionToFile(clusterFeatureCollection,
          Paths.get("data", "debug", "geojson", "0-clusterFeatureCollection.geojson"));
      // TODO: debug end

      // Шаг 2.1: Вычисление Bounding Box кластера и локализация выборки данных
      // (+500м)
      Envelope localEnvelope = boundingBoxService.calculateLocalEnvelope(cluster);
      LocalIndexContainer localData = boundingBoxService.extractLocalData(localEnvelope, globalIndexes);

      // TODO: debug start OK
      var localFeatures = Stream.of(FeatureMapper.mapGeometriesToFeatures(localData.getConstraints()),
          FeatureMapper.mapGeometriesToFeatures(localData.getNetworks()),
          FeatureMapper.mapGeometriesToFeatures(localData.getNodes()))
          .flatMap((s) -> s)
          .collect(Collectors.toList());

      var localFeatureCollection = FeatureCollection.of(localFeatures);
      DebugUtils.writeFeatureCollectionToFile(localFeatureCollection,
          Paths.get("data", "debug", "geojson", "1-localFeatureCollection.geojson"));
      // TODO: debug end

      // Шаг 2.2: Скелетизация Вороного и сборка ориентированного графа (Directed
      // Roadmap)
      DirectedRoadmapBuilder roadmapBuilder = new DirectedRoadmapBuilder(localData);
      DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> graph = roadmapBuilder.build(cluster);

      // TODO: debug start OK
      DebugUtils.exportToGraphML(graph, "base-graph-" + cluster.get(0).getId() + ".graphml");
      // TODO: debug end

      // Шаг 2.3 и 2.4: Калькуляция весов ребер с учетом изломов и 2D-буферизации
      // (габарит 2.0м)
      IntegratedEdgeWeightCalculator weightCalculator = new IntegratedEdgeWeightCalculator(
          collisionService,
          localData.getConstraints(),
          cluster);

      // Актуализируем веса ребер в JGrapht на основе штрафных коэффициентов и
      // коллизий
      for (DefaultWeightedEdge edge : graph.edgeSet()) {
        VirtualNode from = graph.getEdgeSource(edge);
        VirtualNode to = graph.getEdgeTarget(edge);

        double calculatedWeight = weightCalculator.calculateWeight(from, to);
        graph.setEdgeWeight(edge, calculatedWeight);
      }

      // TODO: debug start
      DebugUtils.exportToGraphML(graph, "with-weight-" + cluster.get(0).getId() + ".graphml");
      // TODO: debug end

      // Шаг 2.5: Поиск K кратчайших путей (алгоритм Йена) и топологический контроль
      // валентности (degree <= 4)
      List<RoutingResult> clusterVariants = topologyGenerator.generateNetworkVariants(cluster, graph);
      pipelineResults.add(clusterVariants);
    }

    return pipelineResults;
  }

}
