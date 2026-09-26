package com.ultra.dit.factory;

import java.util.Map;

import com.ultra.dit.constants.FeaturePropertiesStyles;
import com.ultra.dit.dto.geojson.ObjectType;

public class FeaturePropertiesStyleFactory {

  public static Map<String, String> getStyleProperties(Map<String, String> additionalProperties,
      ObjectType objectType) {

    switch (objectType) {
      case OKS_CONNECTION_POINT:
        additionalProperties.putAll(FeaturePropertiesStyles.OKS_CONNECTION_POINT);
        break;
      case HEAT_CHAMBER:
        additionalProperties.putAll(FeaturePropertiesStyles.HEAT_CHAMBER);
        break;
      case HEAT_NETWORK: {
        additionalProperties.putAll(FeaturePropertiesStyles.HEAT_NETWORK);
        break;
      }
      case OKS_EXISTING:
        additionalProperties.putAll(FeaturePropertiesStyles.OKS_EXISTING);
        break;
      case OKS_FUTURE:
        additionalProperties.putAll(FeaturePropertiesStyles.OKS_FUTURE);
        break;
      case RESTRICTION:
        additionalProperties.putAll(FeaturePropertiesStyles.RESTRICTION);
        break;
      case SOURCE:
        additionalProperties.putAll(FeaturePropertiesStyles.SOURCE);
        break;
    }
    return additionalProperties;
  }
}
