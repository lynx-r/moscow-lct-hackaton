package com.ultra.dit.dto.geojson.properties;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ultra.dit.dto.geojson.enums.ObjectType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "object_type", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = SourceProperties.class, name = "source"),
    @JsonSubTypes.Type(value = HeatNetworkSectionProperties.class, name = "heat_network"),
    @JsonSubTypes.Type(value = HeatChamberProperties.class, name = "heat_chamber"),
    @JsonSubTypes.Type(value = OksFutureProperties.class, name = "oks_future"),
    @JsonSubTypes.Type(value = OksConnectionPointProperties.class, name = "oks_connection_point"),
    @JsonSubTypes.Type(value = OksExistingProperties.class, name = "oks_existing"),
    @JsonSubTypes.Type(value = RestrictionProperties.class, name = "restriction"),
    @JsonSubTypes.Type(value = TieInProperties.class, name = "tie_in"),
    @JsonSubTypes.Type(value = HeatNetworkReconstructionProperties.class, name = "heat_network_reconstruction"),
    @JsonSubTypes.Type(value = HeatChamberReconstructionProperties.class, name = "heat_chamber_reconstruction"),
    @JsonSubTypes.Type(value = TechnicalNodeProperties.class, name = "technical_node"),
    @JsonSubTypes.Type(value = VariantSummaryProperties.class, name = "variant_summary"),
    @JsonSubTypes.Type(value = GraphEdgeProperties.class, name = "graph_edge"),
})
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class FeatureProperties {
  private String id;

  private ObjectType objectType;

  /** ID варианта */
  private String variantId;

  @Builder.Default
  private Map<String, JsonNode> additionalProperties = new HashMap<>();

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
  public void addAdditionalProperty(String key, JsonNode value) {
    this.additionalProperties.put(key, value);
  }

  @JsonAnyGetter
  public Map<String, JsonNode> getAdditionalProperties() {
    return this.additionalProperties;
  }

  public void addAdditionalTextProperty(String key, String value) {
    this.additionalProperties.put(key, JsonNodeFactory.instance.textNode(value));
  }

  public void addAdditionalProperties(Map<String, JsonNode> additionalProperties) {
    this.additionalProperties.putAll(additionalProperties);
  }
}
