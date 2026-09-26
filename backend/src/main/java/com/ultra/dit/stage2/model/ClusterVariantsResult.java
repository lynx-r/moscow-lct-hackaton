package com.ultra.dit.stage2.model;

import java.util.List;

import com.ultra.dit.dto.geojson.FeatureCollection;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClusterVariantsResult {
  private List<FeatureCollection> variants;
}
