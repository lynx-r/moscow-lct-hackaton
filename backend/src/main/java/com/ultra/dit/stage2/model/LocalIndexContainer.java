package com.ultra.dit.stage2.model;

import java.util.Collections;
import java.util.List;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

import lombok.Getter;

/**
 * Контейнер для локализованной микро-выборки пространственных данных.
 * Объекты извлекаются из глобальных индексов для конкретного расчетного окна.
 */
@Getter
public final class LocalIndexContainer {
  private final List<Geometry> constraints;
  private final List<Geometry> networks;
  private final List<Geometry> nodes;
  private final Envelope executionEnvelope;

  public LocalIndexContainer(List<Geometry> constraints, List<Geometry> networks,
      List<Geometry> nodes, Envelope executionEnvelope) {
    this.constraints = Collections.unmodifiableList(constraints);
    this.networks = Collections.unmodifiableList(networks);
    this.nodes = Collections.unmodifiableList(nodes);
    this.executionEnvelope = executionEnvelope;
  }
}
