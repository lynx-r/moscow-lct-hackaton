package com.ultra.dit.service.stage2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Point;

import com.ultra.dit.dto.stage2.OksObject;

/**
 * Шаг 2.0. Автоматическая кластеризация ОКС по пространственной близости.
 */
public class OksClusteringService {
  /**
   * Если целевые точки подключения двух ОКС находятся на расстоянии <= 100
   * метров, они объединяются в один кластер.
   *
   * @param oksList Список перспективных ОКС для кластеризации
   * @return Список списков (кластеров) объектов ОКС
   */
  public List<List<OksObject>> clusterOksObjects(List<OksObject> oksList) {
    if (oksList == null || oksList.isEmpty()) {
      return Collections.emptyList();
    }

    int n = oksList.size();
    int[] parent = new int[n];
    for (int i = 0; i < n; i++) {
      parent[i] = i;
    }

    // Квадратичный обход допустим для этапа кластеризации потребителей (их
    // количество ограничено),
    // JTS geometry.distance() вызывается на точечных объектах (Point), что
    // выполняется мгновенно.
    for (int i = 0; i < n; i++) {
      Point pI = oksList.get(i).getConnectionPoint();
      for (int j = i + 1; j < n; j++) {
        Point pJ = oksList.get(j).getConnectionPoint();

        // Проверка нормативного расстояния в 100 метров
        if (pI.distance(pJ) <= 100.0) {
          union(parent, i, j);
        }
      }
    }

    // Группировка индексов по их корневому родителю в DSU
    Map<Integer, List<OksObject>> clusterMap = new HashMap<>();
    for (int i = 0; i < n; i++) {
      int root = find(parent, i);
      clusterMap.computeIfAbsent(root, k -> new ArrayList<>()).add(oksList.get(i));
    }

    return new ArrayList<>(clusterMap.values());
  }

  // Вспомогательный метод DSU: Поиск корня с компрессией путей
  private int find(int[] parent, int i) {
    if (parent[i] == i) {
      return i;
    }
    parent[i] = find(parent, parent[i]);
    return parent[i];
  }

  // Вспомогательный метод DSU: Объединение множеств
  private void union(int[] parent, int x, int y) {
    int rootX = find(parent, x);
    int rootY = find(parent, y);
    if (rootX != rootY) {
      parent[rootX] = rootY;
    }
  }
}
