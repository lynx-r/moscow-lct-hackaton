package com.ultra.dit.dto.geojson;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class OksFutureProperties extends FeatureProperties {

  @JsonProperty("heat_load")
  private Double heatLoad;

  @JsonProperty("flow_tph")
  private Double flowTph;

}
