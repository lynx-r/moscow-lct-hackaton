package com.ultra.dit.dto.geojson;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class OksConnectionPointProperties extends FeatureProperties {

  @JsonProperty("oks_id")
  private String oksId;

  @JsonProperty("flow_tph")
  private Double flowTph;

}
