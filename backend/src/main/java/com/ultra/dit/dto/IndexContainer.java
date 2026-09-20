package com.ultra.dit.dto;

import org.locationtech.jts.index.strtree.STRtree;

import lombok.Value;

@Value
public class IndexContainer {
  private final STRtree constraintsTree;
  private final STRtree networksTree;
  private final STRtree nodesTree;
}
