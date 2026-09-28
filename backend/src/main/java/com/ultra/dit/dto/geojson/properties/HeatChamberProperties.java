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
public class HeatChamberProperties extends FeatureProperties {
  @Builder.Default
  private ObjectType objectType = ObjectType.HEAT_CHAMBER;

  /** Условный диаметр, мм */
  private Integer diameter;

  private String upstreamObjectId;

  /** Стоимость участка, руб. */
  private BigDecimal cost;
}
