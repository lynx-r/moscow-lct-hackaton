package com.ultra.dit.dto.stage2;

import lombok.Getter;
import lombok.Value;

/**
 * Вектор для расчета углов поворота (Virtual Web)
 * 
 * Vector2D
 */
@Getter
public class Vector2D {
  private final double x;
  private final double y;

  public Vector2D(double x, double y) {
    double len = Math.hypot(x, y);
    this.x = len > 1e-9 ? x / len : 0;
    this.y = len > 1e-9 ? y / len : 0;
  }

  // Угол между векторами в градусах [0, 180]
  public double angleWith(Vector2D other) {
    double dot = this.x * other.x + this.y * other.y;
    if (dot > 1.0) {
      dot = 1.0;
    }
    if (dot < -1.0) {
      dot = -1.0;
    }
    return Math.toDegrees(Math.acos(dot));
  }
}
