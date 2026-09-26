package com.ultra.dit.dto.geojson;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class HeatNetworkProperties extends FeatureProperties {

  @JsonProperty("diameter")
  private Integer diameter;

  @JsonProperty("upstream_object_id")
  private String upstreamObjectId;

}
