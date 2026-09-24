package com.ultra.dit.stage2.graph;

import org.locationtech.jts.geom.Coordinate;

import com.ultra.dit.stage2.model.Vector2D;

/**
 * Шаг 2.3. Калькулятор весов ребер (Edge Weight) с матрицей приоритетов углов
 * поворота.
 * Рассчитывает итоговую стоимость прокладки: Физическая длина + Метрический
 * штраф за излом.
 */
public class EdgeWeightCalculator {

  // Величины штрафов в метрическом эквиваленте (условные метры удорожания трассы)
  private static final double PENALTY_STRAIGHT = 0.0; // 0° — Идеально прямая линия
  private static final double PENALTY_90 = 0.0; // 90° — Наивысший стандартный приоритет
  private static final double PENALTY_45 = 15.0; // 45° — Низкий штраф
  private static final double PENALTY_30_60 = 35.0; // 30° или 60° — Средний штраф
  private static final double PENALTY_ARBITRARY_SHARP = 80.0; // Произвольные острые углы <= 90°

  private static final double EPSILON = 1.5; // Допуск на погрешность геометрии в градусах

  /**
   * Динамически вычисляет штраф за угол поворота между двумя последовательными
   * участками трубы.
   * 
   * @param p1 Начало предыдущего сегмента
   * @param p2 Точка излома (текущая вершина соединения)
   * @param p3 Конец текущего сегмента
   * @return Метрический штраф (или Double.MAX_VALUE для запрещенных тупых углов)
   */
  public double calculateWeight(Coordinate p1, Coordinate p2, Coordinate p3) {
    // Вектор захода (откуда пришли в точку p2)
    Vector2D dirIn = new Vector2D(p2.x - p1.x, p2.y - p1.y);
    // Вектор выхода (куда движемся из точки p2)
    Vector2D dirOut = new Vector2D(p3.x - p2.x, p3.y - p2.y);

    // Математически точный угол изменения траектории
    double deltaAlpha = dirIn.angleWith(dirOut);

    // Определение штрафа по матрице приоритетов ТЗ
    double anglePenalty = determineAnglePenalty(deltaAlpha);

    // Если штраф заградительный — блокируем ребро
    if (anglePenalty == Double.MAX_VALUE) {
      return Double.MAX_VALUE;
    }

    return anglePenalty;
  }

  /**
   * Внутреннее сопоставление угла изменения направления с ТЗ матрицы штрафов.
   */
  private double determineAnglePenalty(double angle) {
    // Защита: Углы > 90° (тупые углы, развороты назад) полностью запрещены
    if (angle > 90.0 + EPSILON) {
      return Double.MAX_VALUE;
    }

    // \Delta\alpha = 0° (Прямая линия)
    if (angle <= EPSILON) {
      return PENALTY_STRAIGHT;
    }

    // \Delta\alpha = 90° (Стандартный поворот)
    if (Math.abs(angle - 90.0) <= EPSILON) {
      return PENALTY_90;
    }

    // \Delta\alpha = 45°
    if (Math.abs(angle - 45.0) <= EPSILON) {
      return PENALTY_45;
    }

    // \Delta\alpha ∈ {30°, 60°}
    if (Math.abs(angle - 30.0) <= EPSILON || Math.abs(angle - 60.0) <= EPSILON) {
      return PENALTY_30_60;
    }

    // Любые другие произвольные углы меньше или равные 90°
    return PENALTY_ARBITRARY_SHARP;
  }
}
