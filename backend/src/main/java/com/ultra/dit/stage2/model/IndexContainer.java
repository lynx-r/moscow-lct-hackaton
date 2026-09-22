package com.ultra.dit.stage2.model;

import java.util.Collections;
import java.util.List;

import org.locationtech.jts.index.strtree.STRtree;

import lombok.Value;

@Value
public class IndexContainer {
  /**
   * Полигоны и линии пространственных ограничений.
   */
  private final STRtree constraintsTree;

  /**
   * Линии существующих тепловых сетей.
   */
  private final STRtree networksTree;

  /**
   * Точечные объекты существующих камер, источников и точек подключения.
   */
  private final STRtree nodesTree;

  /**
   * Список точек подключения ОКС
   */
  private final List<OksObject> oksObjects;

  public IndexContainer(STRtree constraintsTree, STRtree networksTree,
      STRtree nodesTree, List<OksObject> oksObjects) {
    this.constraintsTree = constraintsTree;
    this.networksTree = networksTree;
    this.nodesTree = nodesTree;
    // Защищаем данные от случайных изменений во время трассировки
    this.oksObjects = Collections.unmodifiableList(oksObjects);
  }
}
