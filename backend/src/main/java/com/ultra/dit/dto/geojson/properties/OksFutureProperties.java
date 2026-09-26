package com.ultra.dit.dto.geojson.properties;

import lombok.Data;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
public class OksFutureProperties extends FeatureProperties {

  private Double heatLoad;

  private Double flowTph;

}
