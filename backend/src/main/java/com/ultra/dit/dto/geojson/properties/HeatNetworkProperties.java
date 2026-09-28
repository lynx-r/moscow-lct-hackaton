package com.ultra.dit.dto.geojson.properties;

import java.math.BigDecimal;

import com.ultra.dit.dto.geojson.enums.LayingMethod;
import com.ultra.dit.dto.geojson.enums.ObjectType;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * POJO для представления данных участка тепловой сети.
 */
@Data
@SuperBuilder
@NoArgsConstructor
public class HeatNetworkProperties extends FeatureProperties {
  @Builder.Default
  private ObjectType objectType = ObjectType.HEAT_NETWORK;

  /**
   * ID точки врезки, тепловой камеры, технического узла или точки подключения ОКС
   */
  private String startNodeId;

  /** ID объекта, соответствующего второму концу участка */
  private String endNodeId;

  /** Расчётный расход, т/ч */
  private BigDecimal flowTph;

  /** Условный диаметр, мм */
  private Integer diameter;

  private String upstreamObjectId;

  /** Длина участка, м */
  private Double length;

  /**
   * Способ прокладки: Обычная прокладка (base) или специальный проход (special)
   */
  private LayingMethod layingMethod;

  /**
   * Глубина до верха расчётного габарита в начале, м; для основной двумерной
   * задачи – null
   */
  private Double depthStart;

  /**
   * Глубина до верха расчётного габарита в конце, м; для основной двумерной
   * задачи – null
   */
  private Double depthEnd;

  /** Стоимость участка, руб. */
  private BigDecimal cost;
}
