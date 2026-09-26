package com.ultra.dit.dto.geojson.properties;

import java.math.BigDecimal;

import com.ultra.dit.dto.geojson.enums.ObjectType;

import lombok.Data;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
public class TieInProperties extends FeatureProperties {

  /**
   * ID существующего участка тепловой сети или существующей тепловой камеры, в
   * которой выполняется врезка
   */
  private String existingObjectId;

  /** Тип существующего объекта (heat_network или heat_chamber) */
  private ObjectType existingObjectType;

  /**
   * Диаметр существующего участка либо входное значение максимального
   * существующего диаметра камеры, мм
   */
  private Integer existingDiameter;

  /** Условный диаметр новой тепловой сети в точке врезки, мм */
  private Integer requiredDiameter;

  /** Стоимость одной врезки, руб. */
  private BigDecimal cost;
}