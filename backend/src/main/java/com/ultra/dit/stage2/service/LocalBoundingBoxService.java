package com.ultra.dit.stage2.service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;

import com.ultra.dit.aop.LogExecutionTime;
import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.enums.ObjectType;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.stage2.config.Stage2Properties;
import com.ultra.dit.stage2.model.IndexContainer;
import com.ultra.dit.stage2.model.LocalIndexContainer;
import com.ultra.dit.stage2.model.LocalIndexContainer.TargeOksNode;
import com.ultra.dit.stage2.model.OksObject;
import com.ultra.dit.util.Distance;

import lombok.RequiredArgsConstructor;

/**
 * Шаг 2.1. Сервис динамической локализации геоданных.
 * Вырезает географическое окно (Bounding Box) вокруг кластера потребителей
 * с технологическим буфером +500 метров для оптимизации путевого поиска.
 */
@RequiredArgsConstructor
public class LocalBoundingBoxService {

  private final Stage2Properties properties;

  /**
   * Вычисляет общий минимальный охватывающий прямоугольник (Envelope)
   * для кластера ОКС с учетом технологического буфера +500м.
   */
  public Envelope calculateLocalEnvelope(List<OksObject> cluster) {
    Envelope envelope = new Envelope();

    // Накапливаем пространственный охват точек подключения ОКС
    for (OksObject oks : cluster) {
      envelope.expandToInclude(oks.getConnectionPoint().getCoordinate());
      // Также учитываем габариты самого здания ОКС, чтобы окно гарантированно
      // покрывало объект полностью
      envelope.expandToInclude(oks.getBuildingPolygon().getEnvelopeInternal());
    }

    // Расширяем Envelope во все стороны на 500 метров (ТЗ)
    envelope.expandBy(properties.getClusterBufferMeters());
    return envelope;
  }

  /**
   * Выполняет пространственную фильтрацию через R-Tree (STRtree.query)
   * и формирует изолированный контекст данных для текущего шага трассировки.
   * 
   * @param clusterId
   */
  @LogExecutionTime
  @SuppressWarnings("unchecked")
  public LocalIndexContainer extractLocalData(Envelope localEnvelope, IndexContainer globalIndexes,
      List<OksObject> cluster, int clusterId) {
    // Извлекаем пространственные ограничения (Зоны запрета, дороги, здания)
    List<Feature<? extends FeatureProperties>> localConstraints = globalIndexes.getConstraintsTree()
        .query(localEnvelope);

    // Извлекаем существующие тепловые сети (Линейные объекты)
    List<Feature<? extends FeatureProperties>> localNetworks = globalIndexes.getNetworksTree().query(localEnvelope);

    // Извлекаем существующие камеры, источники, точки подключения (Точечные
    // объекты)
    List<Feature<? extends FeatureProperties>> localNodes = globalIndexes.getNodesTree().query(localEnvelope);

    // Оставляем только топ-5 геометрически ближайших камер врезки к потребителям
    // кластера
    var clusterCenter = cluster.get(0).getConnectionPoint().getCoordinate();
    List<Feature<? extends FeatureProperties>> sourceNodes = localNodes.stream()
        .filter((n) -> n.getProperties().getObjectType() != ObjectType.OKS_CONNECTION_POINT)
        .sorted(Comparator.comparingDouble(v -> Distance.distanceSq(v.getGeometry().getCoordinate(), clusterCenter)))
        // TODO: ?????? limit ClosesSourcesCount
        // .limit(properties.getClosesSourcesCount())
        .collect(Collectors.toList());

    // 1. СТРОИМ ЛОКАЛЬНЫЙ ПРОСТРАНСТВЕННЫЙ ИНДЕКС ОГРАНИЧЕНИЙ И ПРЕПЯТСТВИЙ
    var constraintsTree = new STRtree();

    // Вкачиваем в дерево все здания/ограничения из вашего стрима
    localConstraints.stream().map(c -> c.getGeometry()).forEach(geom -> {
      constraintsTree.insert(geom.getEnvelopeInternal(), geom);
    });

    // Собираем все доступные целевые координаты существующих сетей (точки врезки)
    var sourceTree = new STRtree();
    sourceNodes.stream().forEach(node -> {
      var geometry = node.getGeometry();
      var c = geometry.getCoordinate();
      sourceTree.insert(new Envelope(c), new LocalIndexContainer.ExistingSourceNode(c, node.getProperties()));
      constraintsTree.insert(geometry.getEnvelopeInternal(), geometry);
    });

    // Собираем целевые координаты точек подключения ОКС
    var targetOksTree = new STRtree();
    cluster.forEach((oks) -> {
      var geometry = oks.getConnectionPoint();
      var c = geometry.getCoordinate();
      targetOksTree.insert(new Envelope(c), new TargeOksNode(c, oks));
      constraintsTree.insert(geometry.getEnvelopeInternal(), geometry);
    });

    constraintsTree.build();
    sourceTree.build();
    targetOksTree.build();

    return new LocalIndexContainer(localConstraints, localNetworks, localNodes, sourceNodes, constraintsTree,
        sourceTree, targetOksTree, localEnvelope, cluster, clusterId);
  }

}