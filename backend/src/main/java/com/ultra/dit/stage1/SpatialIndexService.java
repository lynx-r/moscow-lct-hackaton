package com.ultra.dit.stage1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ultra.dit.aop.LogExecutionTime;
import com.ultra.dit.common.factory.FeaturePropertiesStyleFactory;
import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.enums.ObjectType;
import com.ultra.dit.dto.geojson.enums.RestrictionType;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.dto.geojson.properties.OksConnectionPointProperties;
import com.ultra.dit.dto.geojson.properties.RestrictionProperties;
import com.ultra.dit.exception.ParseException;
import com.ultra.dit.exception.SpatialIndexException;
import com.ultra.dit.stage2.model.IndexContainer;
import com.ultra.dit.stage2.model.OksObject;

@Service
public class SpatialIndexService {

  private final Double oksConnectionPointBufferMeters;
  private FeatureProperties properties;

  public SpatialIndexService(
      @Value("${app.solution.stage2.oksConnectionPointBufferMeters:2.0}") Double oksConnectionPointBufferMeters) {
    this.oksConnectionPointBufferMeters = oksConnectionPointBufferMeters;
  }

  @LogExecutionTime
  public IndexContainer buildSpatialIndices(Stream<Feature<? extends FeatureProperties>> inputStream) {

    var constraintsTree = new STRtree();
    var networksTree = new STRtree();
    var nodesTree = new STRtree();

    // Временный список для накопления точек подключения, у которых еще нет полигона
    List<Feature<OksConnectionPointProperties>> tempPointsBuffer = Collections.synchronizedList(new ArrayList<>());

    inputStream.forEachOrdered(feature -> {
      if (feature == null || feature.getGeometry() == null) {
        return;
      }

      properties = feature.getProperties();
      var objectType = properties.getObjectType();

      var styleProperties = FeaturePropertiesStyleFactory.getStyleProperties(properties.getAdditionalProperties(),
          objectType);
      properties.addAdditionalProperties(styleProperties);

      // Вставка по EnvelopeInternal большие объекты вроде restriction_type=railway
      // будут попадать в локальную выборку
      var envelope = feature.getGeometry().getEnvelopeInternal();

      // TODO: добавить оставшиеся object_type
      switch (objectType) {
        case RESTRICTION: {
          constraintsTree.insert(envelope, feature);
          break;
        }
        case HEAT_NETWORK:
          networksTree.insert(envelope, feature);
          break;
        case SOURCE:
        case HEAT_CHAMBER:
          nodesTree.insert(envelope, feature);
          break;
        case OKS_CONNECTION_POINT: {
          var oksPoint = (Feature<OksConnectionPointProperties>) feature;
          // Временно откладываем точку, так как её полигон может быть дальше по стриму
          tempPointsBuffer.add(oksPoint);

          nodesTree.insert(envelope, feature);
          break;
        }
      }
    });

    // Фиксация индексов перед отдачей в алгоритмы трассировки
    constraintsTree.build();
    networksTree.build();
    nodesTree.build();

    var oksBuffer = spatialJoin(tempPointsBuffer, constraintsTree);

    if (oksBuffer.isEmpty()) {
      throw new SpatialIndexException("Не найдены Feature object_type=oks_connection_point");
    }

    if (nodesTree.isEmpty()) {
      throw new SpatialIndexException("Не найдены Feature object_type=oks_connection_point и object_type=heat_chamber");
    }

    // TODO: проверять RESTRICTION и HEAT_NETWORK?

    return new IndexContainer(constraintsTree, networksTree, nodesTree, oksBuffer);
  }

  // Пространственная сшивка (Spatial Join) точек с ближайшими полигонами ОКС
  private List<OksObject> spatialJoin(List<Feature<OksConnectionPointProperties>> tempFeatureBuffer,
      STRtree constraintsTree) {
    // Инициализируем потокобезопасный список для сбора ОКС объектов
    var oksBuffer = Collections.synchronizedList(new ArrayList<OksObject>());

    for (var feature : tempFeatureBuffer) {
      var connectionPoint = (Point) feature.getGeometry();

      // Делаем микро-окно вокруг точки (расширяем на пару метров, чтобы поймать
      // касание или пересечение)
      var searchEnvelope = connectionPoint.getEnvelopeInternal();
      // oksConnectionPointBufferMeters метра буфера для точки подключения на границе
      // здания
      searchEnvelope.expandBy(oksConnectionPointBufferMeters);

      // Запрашиваем из локального R-Tree только кандидатов, находящихся рядом с
      // точкой
      var candidates = constraintsTree.query(searchEnvelope);

      Geometry buildingPolygon = null;
      double minDistance = Double.MAX_VALUE;

      for (Object candidate : candidates) {
        if (candidate instanceof Feature) {
          var feat = (Feature) candidate;
          var props = feat.getProperties();

          // Нас интересуют строго ограничения с типов OKS
          if (props.getObjectType() != ObjectType.RESTRICTION) {
            continue;
          }
          var restriction = (RestrictionProperties) props;
          boolean isOksRestriction = restriction.getRestrictionType() == RestrictionType.OKS;
          if (isOksRestriction) {
            var poly = feat.getGeometry();
            double currentDist = connectionPoint.distance(poly);

            // Ищем геометрически самый близкий полигон здания (часто расстояние будет ровно
            // 0.0)
            if (currentDist < minDistance) {
              minDistance = currentDist;
              buildingPolygon = poly;
            }
          }
        }
      }

      // Если по какой-то причине (грязные геоданные) полигон не найден в радиусе 2м,
      // делаем резервный поиск по всему локальному окну без ограничения, чтобы
      // алгоритм не упал
      if (buildingPolygon == null) {
        throw new ParseException("Не найден полигон ОКС для точки подключения");
        // // Фолбэк: берем ближайший полигон ОКС из расширенного окна
        // var fallbackEnvelope = pointGeom.getEnvelopeInternal();
        // fallbackEnvelope.expandBy(100.0);
        // var fallbackCandidates = constraintsTree.query(fallbackEnvelope);
        // for (Object candidate : fallbackCandidates) {
        // if (candidate instanceof Feature) {
        // var feat = (Feature) candidate;
        // var props = feat.getProperties();
        // if (props.getObjectType() == ObjectType.RESTRICTION &&
        // props.getRestrictionType() == RestrictionType.OKS) {
        // buildingPolygon = feat.getGeometry();
        // break; // берем первый попавшийся в 100м
        // }
        // }
        // }
      }

      // Создаем полноценный валидный OksObject с привязанным полигоном
      var oks = new OksObject(feature, buildingPolygon);
      oksBuffer.add(oks);
    }

    return oksBuffer;
  }
}
