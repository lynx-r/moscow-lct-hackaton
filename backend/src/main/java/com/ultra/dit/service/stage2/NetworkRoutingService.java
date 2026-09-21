package com.ultra.dit.service.stage2;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.springframework.stereotype.Service;

import com.ultra.dit.dto.IndexContainer;
import com.ultra.dit.dto.stage2.LocalIndexContainer;
import com.ultra.dit.dto.stage2.OksObject;
import com.ultra.dit.dto.stage2.RoutingResult;
import com.ultra.dit.dto.stage2.VirtualNode;
import com.ultra.dit.service.stage2.graph.DirectedRoadmapBuilder;
import com.ultra.dit.service.stage2.graph.IntegratedEdgeWeightCalculator;

import java.util.ArrayList;
import java.util.List;

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
   * @param oksList       Исходный список перспективных объектов капитального
   *                      строительства (ОКС)
   * @param globalIndexes Контейнер глобальных пространственных R-Tree индексов
   * @return Список списков вариантов трассировки (до 3 вариантов на каждый
   *         кластер)
   */
  public List<List<RoutingResult>> executeRoutingPipeline(List<OksObject> oksList, IndexContainer globalIndexes) {
    List<List<RoutingResult>> pipelineResults = new ArrayList<>();

    // Шаг 2.0: Автоматическая пространственная кластеризация ОКС (d <= 100м)
    List<List<OksObject>> clusters = clusteringService.clusterOksObjects(oksList);

    for (List<OksObject> cluster : clusters) {
      // Шаг 2.1: Вычисление Bounding Box кластера и локализация выборки данных
      // (+500м)
      Envelope localEnvelope = boundingBoxService.calculateLocalEnvelope(cluster);
      LocalIndexContainer localData = boundingBoxService.extractLocalData(localEnvelope, globalIndexes);

      // Шаг 2.2: Скелетизация Вороного и сборка ориентированного графа (Directed
      // Roadmap)
      DirectedRoadmapBuilder roadmapBuilder = new DirectedRoadmapBuilder(localData);
      DirectedWeightedMultigraph<VirtualNode, DefaultWeightedEdge> graph = roadmapBuilder.build(cluster);

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

      // Шаг 2.5: Поиск K кратчайших путей (алгоритм Йена) и топологический контроль
      // валентности (degree <= 4)
      List<RoutingResult> clusterVariants = topologyGenerator.generateNetworkVariants(cluster, graph);
      pipelineResults.add(clusterVariants);
    }

    return pipelineResults;
  }
}
