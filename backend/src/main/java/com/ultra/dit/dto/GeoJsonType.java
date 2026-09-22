package com.ultra.dit.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum GeoJsonType {
  FEATURE_COLLECTION("FeatureCollection"),
  FEATURE("Feature"),
  LINE_STRING("LineString"),
  POINT("Point");

  private final String value;

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static GeoJsonType fromValue(String value) {
    for (var val : values()) {
      if (val.value.equalsIgnoreCase(value)) {
        return val;
      }
    }
    // Если пришло неизвестное значение, можно вернуть null или выбросить ошибку
    return null;
  }
}
