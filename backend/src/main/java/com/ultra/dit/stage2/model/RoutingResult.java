package com.ultra.dit.stage2.model;

import java.util.List;

import com.ultra.dit.dto.Feature;

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
  private final List<Feature> newPipelines; // Новые участки сети
  private final List<Feature> chambers; // Новые/задействованные камеры

  private final int clusterId;
  private final int variantId;
  private final boolean connected; // Флаг успешности трассировки

  public static RoutingResult empty(int clusterId) {
    return new RoutingResult(List.of(), List.of(), clusterId, 0, false);
  }
}