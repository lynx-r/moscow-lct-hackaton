package com.ultra.dit.stage2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import lombok.Data;

@Data
@Component
@RequestScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
@ConfigurationProperties(prefix = "app.solution.stage2")
public class Stage2Properties {
  // Вычисляет общий минимальный охватывающий прямоугольник (Envelope)
  // для кластера ОКС с учетом технологического буфера
  private double clusterBufferMeters = 200.0;
  // Пессимистичный радиус буфера для максимального ДУ 1400 мм по ТЗ (в метрах)
  private double bufferRadiusMeters = 2.0;
  // Размер кластера
  private double clusterSizeMeters = 100.0;
  // N метра буфера для точки подключения на границе здания
  private double oksConnectionPointBufferMeters = 2.0;
  // Количество вариантов для генерации
  private int maxVariants = 10;
  // Максимальная степень узла для камер по ТЗ
  private int maxValence = 4;
  // из скольки кратчайших путей выбирать
  private int chooseFromPathsCount = 5;
  // Ограничиваемся 3 параллельными кластерами
  private int parallelClusterCount = 3;
  // Критерий уникальности=если более 70% вершин совпадают, считаем варианты
  // идентичными
  private double pathUniquePercent = 0.7;
  // 5 ближайших точек врезки при построении сети кластера
  private int closesSourcesCount = 5;

  public Stage2Properties copy() {
    Stage2Properties copy = new Stage2Properties();
    copy.setClusterBufferMeters(clusterBufferMeters);
    copy.setBufferRadiusMeters(bufferRadiusMeters);
    copy.setClusterSizeMeters(clusterSizeMeters);
    copy.setOksConnectionPointBufferMeters(oksConnectionPointBufferMeters);
    copy.setMaxVariants(maxVariants);
    copy.setMaxValence(maxValence);
    copy.setChooseFromPathsCount(chooseFromPathsCount);
    copy.setParallelClusterCount(parallelClusterCount);
    copy.setPathUniquePercent(pathUniquePercent);
    copy.setClosesSourcesCount(closesSourcesCount);
    return copy;
  }
}
