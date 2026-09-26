package com.ultra.dit.dto.geojson;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "object_type", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = SourceProperties.class, name = "source"),
    @JsonSubTypes.Type(value = HeatNetworkProperties.class, name = "heat_network"),
    @JsonSubTypes.Type(value = HeatChamberProperties.class, name = "heat_chamber"),
    @JsonSubTypes.Type(value = OksConnectionPointProperties.class, name = "oks_connection_point"),
    @JsonSubTypes.Type(value = OksFutureProperties.class, name = "oks_future"),
    @JsonSubTypes.Type(value = RestrictionProperties.class, name = "restriction")
})
public class FeatureProperties {
  @JsonProperty("id")
  private String id;

  @JsonProperty("object_type")
  private ObjectType objectType;

  @Builder.Default
  private Map<String, String> additionalProperties = new HashMap<>();

  /**
   * Geometry: Point
   * 
   * @return
   */
  @JsonIgnore
  public boolean isPointGeometry() {
    if (objectType == null) {
      return false;
    }
    switch (objectType) {
      case SOURCE:
      case HEAT_CHAMBER:
      case OKS_CONNECTION_POINT:
        return true;
      default:
        return false;
    }
  }

  /**
   * Geometry: LineString / MultiLineString / Polygon / MultiPolygon
   * 
   * @return
   */
  @JsonIgnore
  public boolean isComplexGeometry() {
    if (objectType == null) {
      return false;
    }
    switch (objectType) {
      case HEAT_NETWORK:
      case OKS_FUTURE:
      case OKS_EXISTING:
      case RESTRICTION:
        return true;
      default:
        return false;
    }
  }

  @JsonAnySetter
  public void addAdditionalProperty(String key, String value) {
    this.additionalProperties.put(key, value);
  }

  @JsonAnyGetter
  public Map<String, String> getAdditionalProperties() {
    return this.additionalProperties;
  }

  public void addAdditionalProperties(Map<String, String> additionalProperties) {
    this.additionalProperties.putAll(additionalProperties);
  }
}
