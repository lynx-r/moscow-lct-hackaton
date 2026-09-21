package com.ultra.dit.dto;

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
}
