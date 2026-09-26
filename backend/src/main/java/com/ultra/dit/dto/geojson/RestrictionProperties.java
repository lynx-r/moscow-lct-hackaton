package com.ultra.dit.dto.geojson;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class RestrictionProperties extends FeatureProperties {

  @JsonProperty("restriction_type")
  private RestrictionType restrictionType;

}
