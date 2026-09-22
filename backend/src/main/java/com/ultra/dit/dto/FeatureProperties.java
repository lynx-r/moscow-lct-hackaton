package com.ultra.dit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeatureProperties {
  @JsonProperty("id")
  private String id;

  @JsonProperty("object_type")
  private ObjectType objectType;

  @JsonProperty("diameter")
  private Integer diameter;

  @JsonProperty("flow_tph")
  private Double flowTph;

  @JsonProperty("heat_load")
  private Double heatLoad;

  @JsonProperty("oks_id")
  private String oksId;

  @JsonProperty("restriction_type")
  private RestrictionType restrictionType;

  @JsonProperty("upstream_object_id")
  private String upstreamObjectId;
}
