package com.ultra.dit.stage2.service;

import java.util.List;

import org.locationtech.jts.geom.Envelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ultra.dit.aop.LogExecutionTime;
import com.ultra.dit.dto.Feature;
import com.ultra.dit.stage2.model.IndexContainer;
import com.ultra.dit.stage2.model.LocalIndexContainer;
import com.ultra.dit.stage2.model.OksObject;

/**
 * Шаг 2.1. Сервис динамической локализации геоданных.
 * Вырезает географическое окно (Bounding Box) вокруг кластера потребителей
 * с технологическим буфером +500 метров для оптимизации путевого поиска.
 */
@Service
public class LocalBoundingBoxService {

  private final double bufferMeters;

  public LocalBoundingBoxService(
      @Value("${app.solution.stage2.bufferMeters:500.0}") double bufferMeters) {
    this.bufferMeters = bufferMeters;
  }

  /**
   * Вычисляет общий минимальный охватывающий прямоугольник (Envelope)
   * для кластера ОКС с учетом технологического буфера +500м.
   */
  @LogExecutionTime
  public Envelope calculateLocalEnvelope(List<OksObject> cluster) {
    if (cluster == null || cluster.isEmpty()) {
      return new Envelope();
    }

    Envelope envelope = new Envelope();

    // Накапливаем пространственный охват точек подключения ОКС
    for (OksObject oks : cluster) {
      envelope.expandToInclude(oks.getConnectionPoint().getCoordinate());
      // Также учитываем габариты самого здания ОКС, чтобы окно гарантированно
      // покрывало объект полностью
      envelope.expandToInclude(oks.getBuildingPolygon().getEnvelopeInternal());
    }

    // Расширяем Envelope во все стороны на 500 метров (ТЗ)
    // FIXME: расширить в метрах не получилось. Расширяет в градусах
    envelope.expandBy(bufferMeters);
    return envelope;
  }

  /**
   * Выполняет пространственную фильтрацию через R-Tree (STRtree.query)
   * и формирует изолированный контекст данных для текущего шага трассировки.
   */
  @LogExecutionTime
  @SuppressWarnings("unchecked")
  public LocalIndexContainer extractLocalData(Envelope localEnvelope, IndexContainer globalIndexes) {
    if (localEnvelope == null || localEnvelope.isNull()) {
      return new LocalIndexContainer(List.of(), List.of(), List.of(), localEnvelope);
    }

    // Извлекаем пространственные ограничения (Зоны запрета, дороги, здания)
    List<Feature> localConstraints = globalIndexes.getConstraintsTree().query(localEnvelope);

    // Извлекаем существующие тепловые сети (Линейные объекты)
    List<Feature> localNetworks = globalIndexes.getNetworksTree().query(localEnvelope);

    // Извлекаем существующие камеры, источники, точки подключения (Точечные
    // объекты)
    List<Feature> localNodes = globalIndexes.getNodesTree().query(localEnvelope);

    return new LocalIndexContainer(localConstraints, localNetworks, localNodes, localEnvelope);
  }

}