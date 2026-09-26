package com.ultra.dit.dto.geojson.properties;

import java.math.BigDecimal;

import lombok.Data;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
public class HeatChamberProperties extends FeatureProperties {

  /** Условный диаметр, мм */
  private Integer diameter;

  private String upstreamObjectId;

  /** Стоимость участка, руб. */
  private BigDecimal cost;
}
