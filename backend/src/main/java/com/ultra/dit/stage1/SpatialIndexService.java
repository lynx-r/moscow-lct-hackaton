package com.ultra.dit.stage1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;
import org.springframework.stereotype.Service;

import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.ObjectType;
import com.ultra.dit.dto.RestrictionType;
import com.ultra.dit.stage2.model.IndexContainer;
import com.ultra.dit.stage2.model.OksObject;

@Service
public class SpatialIndexService {

  public IndexContainer buildSpatialIndices(Stream<Feature> inputStream) {
    var constraintsTree = new STRtree();
    var networksTree = new STRtree();
    var nodesTree = new STRtree();

    // Временный список для накопления точек подключения, у которых еще нет полигона
    var tempPointsBuffer = Collections.synchronizedList(new ArrayList<TempOksPoint>());

    inputStream.forEachOrdered(feature -> {
      if (feature == null || feature.getGeometry() == null) {
        return;
      }

      var properties = feature.getProperties();
      var objectType = properties.getObjectType();

      var envelope = feature.getGeometry().getEnvelopeInternal();

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
          var id = properties.getId();
          var connectionPoint = (Point) feature.getGeometry();
          var flowTph = properties.getFlowTph();

          // Временно откладываем точку, так как её полигон может быть дальше по стриму
          tempPointsBuffer.add(new TempOksPoint(id, connectionPoint, flowTph));

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

    return new IndexContainer(constraintsTree, networksTree, nodesTree, oksBuffer);
  }

  // Пространственная сшивка (Spatial Join) точек с ближайшими полигонами ОКС
  private List<OksObject> spatialJoin(List<TempOksPoint> tempPointsBuffer, STRtree constraintsTree) {
    // Инициализируем потокобезопасный список для сбора ОКС объектов
    var oksBuffer = Collections.synchronizedList(new ArrayList<OksObject>());

    for (var tempPoint : tempPointsBuffer) {
      Point pointGeom = tempPoint.connectionPoint;

      // Делаем микро-окно вокруг точки (расширяем на пару метров, чтобы поймать
      // касание или пересечение)
      var searchEnvelope = pointGeom.getEnvelopeInternal();
      // 2 метра буфера более чем достаточно для точки подключения на границе здания
      searchEnvelope.expandBy(2.0);

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
          if (props.getObjectType() == ObjectType.RESTRICTION && props.getRestrictionType() == RestrictionType.OKS) {
            var poly = feat.getGeometry();
            double currentDist = pointGeom.distance(poly);

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
        // Фолбэк: берем ближайший полигон ОКС из расширенного окна
        var fallbackEnvelope = pointGeom.getEnvelopeInternal();
        fallbackEnvelope.expandBy(100.0);
        var fallbackCandidates = constraintsTree.query(fallbackEnvelope);
        for (Object candidate : fallbackCandidates) {
          if (candidate instanceof Feature) {
            var feat = (Feature) candidate;
            var props = feat.getProperties();
            if (props.getObjectType() == ObjectType.RESTRICTION && props.getRestrictionType() == RestrictionType.OKS) {
              buildingPolygon = feat.getGeometry();
              break; // берем первый попавшийся в 100м
            }
          }
        }
      }

      // Создаем полноценный валидный OksObject с привязанным полигоном
      var oks = new OksObject(tempPoint.id, pointGeom, buildingPolygon, tempPoint.flowTph);
      oksBuffer.add(oks);
    }

    return oksBuffer;
  }

  // Вспомогательный легковесный рекорд (или внутренний класс) для первого шага
  private static class TempOksPoint {
    final String id;
    final Point connectionPoint;
    final double flowTph;

    TempOksPoint(String id, Point connectionPoint, double flowTph) {
      this.id = id;
      this.connectionPoint = connectionPoint;
      this.flowTph = flowTph;
    }
  }
}
