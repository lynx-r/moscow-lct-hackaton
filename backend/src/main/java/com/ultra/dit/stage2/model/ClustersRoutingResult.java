package com.ultra.dit.stage2.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClustersRoutingResult {
  private List<ClusterVariantsResult> routes;
}
