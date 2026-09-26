package com.ultra.dit.dto.geojson.properties;

import java.math.BigDecimal;

import lombok.Data;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
public class ReconstructedHeatChamberProperties extends FeatureProperties {

  /**
   * ID входного объекта heat_network, к которому относится реконструируемая часть
   */
  private String existingObjectId;

  /** Текущий условный диаметр, мм */
  private Integer existingDiameter;

  /** Минимальный подходящий диаметр после подключения, мм */
  private Integer requiredDiameter;

  /** Стоимость реконструкции этой части существующей тепловой сети, руб. */
  private BigDecimal cost;

}
