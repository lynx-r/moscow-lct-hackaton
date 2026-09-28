package com.ultra.dit.util;

import com.ultra.dit.dto.DebugDataClusterId;
import com.ultra.dit.dto.geojson.enums.ObjectType;

public class Id {

  public static String idByObjectType(int clusterId, int variantId, ObjectType objectType) {
    var suffix = suffixByObjectType(objectType);
    return clusterId + ":" + variantId + ":" + suffix;
  }

  public static String idForTypeVariantSummary(int clusterId, int variantId) {
    return idByObjectType(clusterId, variantId, ObjectType.VARIANT_SUMMARY);
  }

  public static String variantId(int clusterId, int variantId) {
    return clusterId + ":" + variantId + "_variant";
  }

  public static DebugDataClusterId parseId(String id) {
    var clusterId = id.split(":")[0];
    var variantId = id.split(":")[1];
    return new DebugDataClusterId(clusterId, variantId);
  }

  private static String suffixByObjectType(ObjectType objectType) {
    if (objectType == null) {
      return "undefined";
    }
    switch (objectType) {
      case GRAPH_EDGE:
        return "edge";
      case HEAT_CHAMBER:
        return "ch";
      case HEAT_CHAMBER_RECONSTRUCTION:
        return "ch_recon";
      case HEAT_NETWORK:
        return "net";
      case HEAT_NETWORK_RECONSTRUCTION:
        return "net_recon";
      case OKS_CONNECTION_POINT:
        return "oks_conn";
      case OKS_EXISTING:
        return "oks_ex";
      case OKS_FUTURE:
        return "oks_ft";
      case RESTRICTION:
        return "restr";
      case SOURCE:
        return "src";
      case TECHNICAL_NODE:
        return "tec_n";
      case TIE_IN:
        return "tie_in";
      case VARIANT_SUMMARY:
        return "summary";
      default:
        return "v";
    }
  }
}
