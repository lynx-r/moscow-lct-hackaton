package com.ultra.dit.stage2.model;

import java.util.List;

import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

import lombok.Value;

/**
 * Результат трассировки для вывода из сервиса
 * 
 * RoutingResult
 */
@Value
public class RoutingResult {
  private final List<LineString> newPipelines; // Новые участки сети
  private final List<Point> chambers; // Новые/задействованные камеры
  private final boolean connected; // Флаг успешности трассировки
}