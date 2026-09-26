package com.ultra.dit.stage2_test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.FeatureCollection;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.stage1.SpatialIndexService;
import com.ultra.dit.stage1.TransformService;
import com.ultra.dit.stage2.model.ClusterVariantsResult;
import com.ultra.dit.stage2.model.ClustersRoutingResult;
import com.ultra.dit.stage2.model.RoutingResult;
import com.ultra.dit.stage2.service.NetworkRoutingService;

@Service
public class ProcessStage2RoutesService {

  private final TransformService transformService;
  private final NetworkRoutingService networkRoutingService;
  private final SpatialIndexService spatialIndexService;

  public ProcessStage2RoutesService(
      TransformService transformService,
      SpatialIndexService spatialIndexService,
      NetworkRoutingService networkRoutingService) {
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
  public ClustersRoutingResult buildRoutes(Stream<Feature<? extends FeatureProperties>> featureCollection,
      boolean transform) {
    Stream<Feature<? extends FeatureProperties>> readyCollection = featureCollection;
    if (transform) {
      readyCollection = transformService.transform4326To32637(featureCollection);
    }

    var indexContainer = spatialIndexService.buildSpatialIndices(readyCollection);

    var routingPipeline = networkRoutingService.executeRoutingPipeline(indexContainer);

    return mapResultsToClustersRoutingResult(routingPipeline, transform);
  }

  /**
   * Возвращает список подключенный кластеров. С 3 вариантами подключения каждого
   * кластера.
   * 
   * @param featureCollection
   * @return
   */
  public FeatureCollection buildRoutesAsFeatureCollection(
      Stream<Feature<? extends FeatureProperties>> featureCollection,
      boolean transform) {
    Stream<Feature<? extends FeatureProperties>> readyCollection = featureCollection;
    if (transform) {
      readyCollection = transformService.transform4326To32637(featureCollection);
    }

    var indexContainer = spatialIndexService.buildSpatialIndices(readyCollection);

    var routingPipeline = networkRoutingService.executeRoutingPipeline(indexContainer);

    return mapResultsToFeatureCollection(routingPipeline, transform);
  }

  public ClustersRoutingResult mapResultsToClustersRoutingResult(List<List<RoutingResult>> routingPipeline,
      boolean transform) {

    @SuppressWarnings("null")
    var joined = joinFeatures(routingPipeline, transform);

    var routes = joined.map(route -> {
      var variant = route.map((s) -> {
        return FeatureCollection.of(s.collect(Collectors.toList()));
      }).collect(Collectors.toList());
      return new ClusterVariantsResult(variant);
    }).collect(Collectors.toList());

    return new ClustersRoutingResult(routes);

  }

  public FeatureCollection mapResultsToFeatureCollection(
      List<List<RoutingResult>> routingPipeline, boolean transform) {
    var transformedRoutes = joinFeatures(routingPipeline, transform);

    var features = transformedRoutes.flatMap(s -> s).flatMap(s -> s).collect(Collectors.toList());
    return FeatureCollection.of(features);
  }

  private Stream<Stream<Stream<Feature<? extends FeatureProperties>>>> joinFeatures(
      List<List<RoutingResult>> routingPipeline, boolean transform) {
    var featureCollections = routingPipeline.stream().map((route) -> {
      return route.stream().map((clusterVariant) -> FeatureMapper.mapRoutingResultToFeatures(clusterVariant));
    });

    @SuppressWarnings("null")
    Stream<Stream<Stream<Feature<? extends FeatureProperties>>>> transformedRoutes = featureCollections;
    if (transform) {
      transformedRoutes = featureCollections.map((route) -> {
        return route.map((clusterVariant) -> transformService.transform32637To4326(clusterVariant));
      });
    }

    return transformedRoutes;
  }
}
