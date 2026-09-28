package com.ultra.dit.dto.geojson.properties;

import com.ultra.dit.dto.geojson.enums.ObjectType;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
public class OksFutureProperties extends FeatureProperties {
  @Builder.Default
  private ObjectType objectType = ObjectType.OKS_CONNECTION_POINT;

  private Double heatLoad;

  private Double flowTph;

}
