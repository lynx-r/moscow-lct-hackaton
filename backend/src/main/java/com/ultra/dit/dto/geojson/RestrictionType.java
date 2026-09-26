package com.ultra.dit.dto.geojson;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum RestrictionType {
  OKS("oks"),
  PARK("park"),
  SOCIAL_AREA("social_area"),
  PROHIBITED_SITE("prohibited_site"),
  WATER("water"),
  RAILWAY("railway"),
  ROAD("road"),
  TRAM_TRACKS("tram_tracks"),
  GAS_PIPELINE("gas_pipeline"),
  POWER_CABLE("power_cable"),
  HEAT_NETWORK("heat_network");

  private final String value;

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static RestrictionType fromValue(String value) {
    for (var val : values()) {
      if (val.value.equalsIgnoreCase(value)) {
        return val;
      }
    }
    // Если пришло неизвестное значение, можно вернуть null или выбросить ошибку
    return null;
  }
}
