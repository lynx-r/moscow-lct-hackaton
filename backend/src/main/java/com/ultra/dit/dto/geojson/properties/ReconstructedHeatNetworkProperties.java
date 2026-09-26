package com.ultra.dit.dto.geojson.properties;

import java.math.BigDecimal;

import lombok.Data;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
public class ReconstructedHeatNetworkProperties extends FeatureProperties {

  /**
   * ID входного объекта heat_network, к которому относится реконструируемая часть
   */
  private String existingObjectId;

  /** Текущий расчётный расход, т/ч */
  private BigDecimal existingFlowTph;

  /** Суммарный расход новых подключений на этой части, т/ч */
  private BigDecimal addedFlowTph;

  /** Расход после подключения новых ОКС, т/ч */
  private BigDecimal calculatedFlowTph;

  /** Текущий условный диаметр, мм */
  private Integer existingDiameter;

  /** Минимальный подходящий диаметр после подключения, мм */
  private Integer requiredDiameter;

  /** Длина реконструируемой геометрической части, м */
  private Double length;

  /** Стоимость реконструкции этой части существующей тепловой сети, руб. */
  private BigDecimal cost;

}
