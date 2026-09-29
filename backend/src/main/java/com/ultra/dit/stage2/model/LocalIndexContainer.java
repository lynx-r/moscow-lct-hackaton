package com.ultra.dit.stage2.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;

import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;

import lombok.Getter;
import lombok.Value;

/**
 * Контейнер для локализованной микро-выборки пространственных данных.
 * Объекты извлекаются из глобальных индексов для конкретного расчетного окна.
 */
@Getter
public final class LocalIndexContainer {
  /**
   * Пространственные ограничения (Зоны запрета, дороги, здания)
   */
  private final List<Feature<? extends FeatureProperties>> constraints;

  /**
   * Существующие тепловые сети (Линейные объекты)
   */
  private final List<Feature<? extends FeatureProperties>> networks;

  /**
   * Существующие источники, точки подключения (Точечные
   * объекты)
   */
  private final List<Feature<? extends FeatureProperties>> nodes;

  /**
   * Источники без точек подключения
   * 
   * @return
   */
  private final List<Feature<? extends FeatureProperties>> sourceNodes;

  /**
   * Общий минимальный охватывающий прямоугольник (Envelope) для
   * кластера ОКС с учетом технологического буфера clusterBufferMeters.
   */
  private final Envelope executionEnvelope;

  private final STRtree targetOksTree;
  private final STRtree sourceTree;
  private final STRtree constraintsTree;

  private final List<OksObject> cluster;
  private final int clusterId;

  public LocalIndexContainer(
      List<Feature<? extends FeatureProperties>> constraints,
      List<Feature<? extends FeatureProperties>> networks,
      List<Feature<? extends FeatureProperties>> nodes,
      List<Feature<? extends FeatureProperties>> sourceNodes,
      STRtree constraintsTree,
      STRtree sourceTree,
      STRtree targetOksTree,
      Envelope executionEnvelope,
      List<OksObject> cluster,
      int clusterId) {
    this.constraints = Collections.unmodifiableList(constraints);
    this.networks = Collections.unmodifiableList(networks);
    this.nodes = Collections.unmodifiableList(nodes);
    this.sourceNodes = Collections.unmodifiableList(sourceNodes);
    this.executionEnvelope = executionEnvelope;
    this.targetOksTree = targetOksTree;
    this.sourceTree = sourceTree;
    this.constraintsTree = constraintsTree;
    this.cluster = cluster;
    this.clusterId = clusterId;
  }

  public boolean isInvalid() {
    // TODO: проверять networks и constraints?
    return nodes.isEmpty() || networks.isEmpty() || executionEnvelope.isNull();
  }

  public Stream<Geometry> getConstraintsGeometryStream() {
    return constraints.stream().map(c -> c.getGeometry());
  }

  public List<Geometry> getConstraintsGeometry() {
    return getConstraintsGeometryStream().collect(Collectors.toList());
  }

  public Stream<Geometry> getNetworksGeometryStream() {
    return networks.stream().map(c -> c.getGeometry());
  }

  public List<Geometry> getNetworksGeometry() {
    return getNetworksGeometryStream().collect(Collectors.toList());
  }

  public Stream<Geometry> getNodesGeometryStream() {
    return nodes.stream().map(c -> c.getGeometry());
  }

  public List<Geometry> getNodesGeometry() {
    return getNodesGeometryStream().collect(Collectors.toList());
  }

  public Stream<Coordinate> getSourceNodesGeometryStream() {
    return sourceNodes.stream().map(c -> c.getGeometry()).flatMap(g -> Arrays.stream(g.getCoordinates()));
  }

  public List<Coordinate> getSourceNodesGeometry() {
    return getSourceNodesGeometryStream().collect(Collectors.toList());
  }

  public Stream<Coordinate> getClusterOksCoordinatesStream() {
    return cluster.stream().map(oks -> oks.getConnectionPoint().getCoordinate());
  }

  @Value
  public static class ExistingSourceNode {
    private final Coordinate coordinate;
    private final FeatureProperties properties;
  }

  @Value
  public static class TargeOksNode {
    private final Coordinate coordinate;
    private final OksObject oks;
  }
}
