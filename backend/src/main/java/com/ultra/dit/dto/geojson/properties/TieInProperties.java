package com.ultra.dit.dto.geojson.properties;

import java.math.BigDecimal;

import com.ultra.dit.dto.geojson.enums.ObjectType;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Если выбранная точка врезки расположена не далее 10 м от существующей
 * тепловой камеры и после подключения к ней будет примыкать не более четырёх
 * участков тепловой сети, врезка выполняется в этой камере. Если условие по
 * числу участков не выполняется либо существующая камера расположена дальше 10
 * м, в точке врезки создаётся новая тепловая камера.
 * 
 * TieInProperties
 */
@Data
@SuperBuilder
@NoArgsConstructor
public class TieInProperties extends FeatureProperties {
  @Builder.Default
  private ObjectType objectType = ObjectType.TIE_IN;

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