package com.ultra.dit.dto.geojson.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum ObjectType {
  SOURCE("source"),
  HEAT_NETWORK("heat_network"),
  HEAT_CHAMBER("heat_chamber"),
  OKS_FUTURE("oks_future"),
  OKS_CONNECTION_POINT("oks_connection_point"),
  OKS_EXISTING("oks_existing"),
  RESTRICTION("restriction"),
  TIE_IN("tie_in"),
  HEAT_NETWORK_RECONSTRUCTION("heat_network_reconstruction"),
  HEAT_CHAMBER_RECONSTRUCTION("heat_chamber_reconstruction"),
  TECHNICAL_NODE("technical_node"),
  VARIANT_SUMMARY("variant_summary"),
  GRAPH_EDGE("graph_edge");

  private final String value;

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static ObjectType fromValue(String value) {
    for (var val : values()) {
      if (val.value.equalsIgnoreCase(value)) {
        return val;
      }
    }
    // Если пришло неизвестное значение, можно вернуть null или выбросить ошибку
    return null;
  }
}
