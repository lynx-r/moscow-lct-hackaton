package com.ultra.dit.dto.geojson.properties;

import java.math.BigDecimal;

import com.ultra.dit.dto.geojson.enums.ObjectType;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
public class HeatChamberReconstructionProperties extends FeatureProperties {
  @Builder.Default
  private ObjectType objectType = ObjectType.HEAT_CHAMBER_RECONSTRUCTION;

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
