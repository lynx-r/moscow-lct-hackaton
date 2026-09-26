package com.ultra.dit.stage2.model;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;

import lombok.Getter;

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
   * Общий минимальный охватывающий прямоугольник (Envelope) для
   * кластера ОКС с учетом технологического буфера clusterBufferMeters.
   */
  private final Envelope executionEnvelope;

  public LocalIndexContainer(List<Feature<? extends FeatureProperties>> constraints,
      List<Feature<? extends FeatureProperties>> networks,
      List<Feature<? extends FeatureProperties>> nodes, Envelope executionEnvelope) {
    this.constraints = Collections.unmodifiableList(constraints);
    this.networks = Collections.unmodifiableList(networks);
    this.nodes = Collections.unmodifiableList(nodes);
    this.executionEnvelope = executionEnvelope;
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
}
