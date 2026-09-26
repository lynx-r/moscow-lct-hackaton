package com.ultra.dit.dto.geojson;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

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

  private Map<String, JsonNode> additionalProperties = new HashMap<>();

  @JsonAnySetter
  public void addAdditionalProperty(String key, JsonNode value) {
    this.additionalProperties.put(key, value);
  }

  @JsonAnyGetter
  public Map<String, JsonNode> getAdditionalProperties() {
    return this.additionalProperties;
  }
}
