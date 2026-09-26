package com.ultra.dit.stage2.model;

import java.util.List;

import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.dto.geojson.properties.VariantSummaryProperties;

import lombok.RequiredArgsConstructor;
import lombok.Value;

/**
 * Результат трассировки для вывода из сервиса
 * 
 * RoutingResult
 */
@Value
@RequiredArgsConstructor(staticName = "of")
public class RoutingResult {
  private final List<Feature<? extends FeatureProperties>> newPipelines; // Новые участки сети
  private final List<Feature<? extends FeatureProperties>> chambers; // Новые/задействованные камеры

  private final VariantSummaryProperties summaryProperties;

  private final boolean connected; // Флаг успешности трассировки

  public static RoutingResult empty(VariantSummaryProperties summary) {
    return new RoutingResult(List.of(), List.of(), summary, false);
  }
}