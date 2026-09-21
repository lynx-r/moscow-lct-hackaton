package com.ultra.dit.stage2.graph;

import org.locationtech.jts.geom.Coordinate;

import com.ultra.dit.stage2.model.NodeType;
import com.ultra.dit.stage2.model.Vector2D;
import com.ultra.dit.stage2.model.VirtualNode;

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
   * Рассчитывает полный вес ребра между двумя узлами.
   *
   * @param from Исходный виртуальный узел (содержит вектор захода в него)
   * @param to   Целевой виртуальный узел
   * @return Стоимость ребра в метрах (или Double.MAX_VALUE при запрещенных углах)
   */
  public double calculateWeight(VirtualNode from, VirtualNode to) {
    Coordinate c1 = from.getCoordinate();
    Coordinate c2 = to.getCoordinate();

    // 1. Физическая длина сегмента в метрах (работаем в проекции UTM)
    double physicalLength = c1.distance(c2);

    // Если это стартовая точка из существующей сети камер, вектора предыдущего
    // захода нет, штрафовать за угол нечего — возвращаем чистую физическую длину.
    if (from.getType() == NodeType.EXISTING_NETWORK_NODE) {
      return physicalLength;
    }

    // 2. Вычисляем текущий вектор движения по ребру (от c1 к c2)
    Vector2D currentDirection = new Vector2D(c2.x - c1.x, c2.y - c1.y);

    // 3. Считаем угол излома \Delta\alpha относительно вектора, под которым мы
    // вошли в узел 'from'
    // ВАЖНО: from.getDirectionIn() смотрит ТУДА ЖЕ, куда двигался поток при входе в
    // узел.
    double deltaAlpha = from.getDirectionIn().angleWith(currentDirection);

    // 4. Определение штрафа по матрице приоритетов ТЗ
    double anglePenalty = determineAnglePenalty(deltaAlpha);

    // Если штраф заградительный — блокируем ребро
    if (anglePenalty == Double.MAX_VALUE) {
      return Double.MAX_VALUE;
    }

    return physicalLength + anglePenalty;
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
