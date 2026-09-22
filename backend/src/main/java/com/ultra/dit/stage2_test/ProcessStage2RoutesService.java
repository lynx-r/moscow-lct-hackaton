package com.ultra.dit.stage2_test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.FeatureCollection;
import com.ultra.dit.dto.GeoJsonType;
import com.ultra.dit.stage1.SpatialIndexService;
import com.ultra.dit.stage1.TransformService;
import com.ultra.dit.stage2.NetworkRoutingService;
import com.ultra.dit.stage2.model.ClusterVariantsResult;
import com.ultra.dit.stage2.model.ClustersRoutingResult;
import com.ultra.dit.stage2.model.IndexContainer;

@Service
public class ProcessStage2RoutesService {

  private final TransformService transformService;
  private final NetworkRoutingService networkRoutingService;
  private final SpatialIndexService spatialIndexService;

  ProcessStage2RoutesService(TransformService transformService,
      SpatialIndexService spatialIndexService, NetworkRoutingService networkRoutingService) {
    this.transformService = transformService;
    this.networkRoutingService = networkRoutingService;
    this.spatialIndexService = spatialIndexService;
  }

  /**
   * Возвращает список подключенный кластеров. С 3 вариантами подключения каждого
   * кластера.
   * 
   * @param featureCollection
   * @return
   */
  public ClustersRoutingResult buildRoutes(Stream<Feature> featureCollection) {
    var readyCollection = transformService.transform4326To32637(featureCollection);

    IndexContainer indexContainer = spatialIndexService.buildSpatialIndices(readyCollection);

    var routingPipeline = networkRoutingService.executeRoutingPipeline(indexContainer);

    var featureCollections = routingPipeline.stream().map((route) -> {
      return route.stream().map((clusterVariant) -> FeatureMapper.mapRoutingResultToFeatures(clusterVariant));
    });

    var transformedRoutes = featureCollections.map((route) -> {
      return route.map((clusterVariant) -> transformService.transform32637To4326(clusterVariant.stream()));
    });

    var routes = transformedRoutes.map(route -> {
      var variants = route.map((variant) -> {
        var features = variant.collect(Collectors.toList());
        return FeatureCollection.of(features);
      }).collect(Collectors.toList());
      return new ClusterVariantsResult(variants);
    }).collect(Collectors.toList());

    return new ClustersRoutingResult(routes);
  }
}
