package com.ultra.dit.dto.geojson.properties;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
public class OksConnectionPointProperties extends FeatureProperties {

  private String oksId;

  private Double flowTph;

}
