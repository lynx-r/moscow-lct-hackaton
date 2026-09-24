package com.ultra.dit.stage2.model;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

import com.ultra.dit.dto.Feature;

import lombok.Getter;

/**
 * Контейнер для локализованной микро-выборки пространственных данных.
 * Объекты извлекаются из глобальных индексов для конкретного расчетного окна.
 */
@Getter
public final class LocalIndexContainer {
  private final List<Feature> constraints;
  private final List<Feature> networks;
  private final List<Feature> nodes;
  private final Envelope executionEnvelope;

  public LocalIndexContainer(List<Feature> constraints, List<Feature> networks,
      List<Feature> nodes, Envelope executionEnvelope) {
    this.constraints = Collections.unmodifiableList(constraints);
    this.networks = Collections.unmodifiableList(networks);
    this.nodes = Collections.unmodifiableList(nodes);
    this.executionEnvelope = executionEnvelope;
  }

  public Stream<Geometry> getConstraintsGeometryStream() {
    return constraints.stream().map(c -> c.getGeometry());
  }

  public List<Geometry> getConstraintsGeometry() {
    return constraints.stream().map(c -> c.getGeometry()).collect(Collectors.toList());
  }

  public Stream<Geometry> getNetworksGeometryStream() {
    return networks.stream().map(c -> c.getGeometry());
  }

  public Stream<Geometry> getNodesGeometryStream() {
    return nodes.stream().map(c -> c.getGeometry());
  }
}
