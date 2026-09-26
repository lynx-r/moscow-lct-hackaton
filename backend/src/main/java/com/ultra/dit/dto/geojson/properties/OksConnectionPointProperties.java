package com.ultra.dit.dto.geojson.properties;

import lombok.Data;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
public class OksConnectionPointProperties extends FeatureProperties {

  private String oksId;

  private Double flowTph;

}
