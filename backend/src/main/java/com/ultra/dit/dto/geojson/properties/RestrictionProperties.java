package com.ultra.dit.dto.geojson.properties;

import com.ultra.dit.dto.geojson.enums.RestrictionType;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
public class RestrictionProperties extends FeatureProperties {

  private RestrictionType restrictionType;

}
