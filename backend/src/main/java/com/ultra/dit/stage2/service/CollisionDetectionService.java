package com.ultra.dit.stage2.service;

import java.util.List;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ultra.dit.aop.LogExecutionTime;
import com.ultra.dit.stage2.model.OksObject;

/**
 * Шаг 2.4. Сервис проверки пространственных ограничений, 2D буферизации
 * и защиты от самоблокирования при прокладке тепловой сети.
 */
@Service
public class CollisionDetectionService {

  // Пессимистичный радиус буфера для максимального ДУ 1400 мм по ТЗ (в метрах)
  private final double bufferRadiusMeters;

  CollisionDetectionService(
      @Value("${app.solution.stage2.bufferRadiusMeters:2.0}") double bufferRadiusMeters) {
    this.bufferRadiusMeters = bufferRadiusMeters;
  }

  /**
   * Проверяет сегмент (ребро графа) на коллизии с пространственными
   * ограничениями.
   *
   * @param segment          Тестируемый отрезок оси будущей трубы
   * @param localConstraints Микро-выборка ограничений из локального окна (Шаг
   *                         2.1)
   * @param activeCluster    Текущий кластер ОКС, для которого строится маршрут
   * @return true, если сегмент безопасен и проходим; false, если обнаружена
   *         коллизия
   */
  @LogExecutionTime
  public boolean isSegmentPassable(LineString segment, List<Geometry> localConstraints, List<OksObject> activeCluster) {
    if (segment == null || segment.isEmpty()) {
      return false;
    }

    // // РАСШИРЕННАЯ ЗАЩИТА ОТ САМОБЛОКИРОВАНИЯ:
    // // Если сегмент напрямую соединяется с целевой точкой подключения ОКС,
    // // мы временно уменьшаем радиус буфера до 0.1м (или полностью отключаем
    // // проверку),
    // // так как это финишный подвод трубы к зданию сквозь его охранную зону.
    // boolean isFinalOksInlet = false;
    // for (OksObject oks : activeCluster) {
    // if (segment.getStartPoint().distance(oks.getConnectionPoint()) < 0.05 ||
    // segment.getEndPoint().distance(oks.getConnectionPoint()) < 0.05) {
    // isFinalOksInlet = true;
    // break;
    // }
    // }

    // 1. Динамическое построение 2D-буфера вокруг оси сегмента
    // Выполняется в планарной метровой проекции (UTM zone 37N), давая честные 2.0
    // метра во все стороны
    // Устанавливаем радиус буфера: 0.1 метра для финального подвода трубы, 2.0
    // метра — для магистрали
    // double radius = isFinalOksInlet ? 0.1 : bufferRadiusMeters;
    Geometry pipelineBuffer = segment.buffer(bufferRadiusMeters);

    // 2. Итерируемся по всем локальным ограничениям, попавшим в выборку
    for (Geometry constraint : localConstraints) {

      // Проверяем, является ли текущее ограничение собственным полигоном ОКС из
      // нашего кластера
      OksObject matchingOks = findMatchingOks(constraint, activeCluster);

      if (matchingOks != null) {
        // Включается ЗАЩИТА ОТ САМОБЛОКИРОВАНИЯ (Исключение из ТЗ):
        // Если данный сегмент пересекает или заходит в буфер СВОЕГО здания ОКС,
        // проверяем, является ли он финальной точкой врезки (заходит внутрь или
        // касается connectionPoint)
        if (segment.intersects(matchingOks.getConnectionPoint())) {
          // Разрешаем проход: коллизия со своим зданием для финишного отрезка
          // игнорируется
          // TODO: почему не break?
          continue;
        }

        // if (segment.distance(matchingOks.getConnectionPoint()) <= 10.0) {
        // continue; // Разрешаем коллизию со своим зданием в зоне подвода
        // }

        // Если отрезок проходит мимо/вдоль своего здания, но не заходит в его
        // connectionPoint,
        // к нему применяются стандартные строгие правила отступа
      }

      // 3. Точное вычисление расстояния между границей буфера трубы и геометрией
      // ограничения
      // JTS distance() возвращает 0.0, если геометрии пересекаются или накладываются
      double distance = pipelineBuffer.distance(constraint);
      if (distance <= 0.0) {
        // Буфер трубы задел или пересек зону запрета — ребро заблокировано
        return false;
      }
    }

    return true;
  }

  /**
   * Вспомогательный метод определения, совпадает ли геометрия ограничения
   * с полигоном одного из ОКС в текущем обрабатываемом кластере.
   */
  private OksObject findMatchingOks(Geometry constraint, List<OksObject> activeCluster) {
    if (!(constraint instanceof Polygon) || activeCluster == null) {
      return null;
    }

    for (OksObject oks : activeCluster) {
      // Сравниваем полигоны через точное равенство топологии JTS
      if (oks.getBuildingPolygon().equalsExact(constraint)) {
        return oks;
      }
    }
    return null;
  }
}