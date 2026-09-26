package com.ultra.dit.dto.geojson.properties;

import com.ultra.dit.dto.geojson.enums.RestrictionType;

import lombok.Data;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
public class RestrictionProperties extends FeatureProperties {

  private RestrictionType restrictionType;

}
