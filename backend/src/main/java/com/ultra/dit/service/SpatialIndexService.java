package com.ultra.dit.service;

import java.util.stream.Stream;

import org.locationtech.jts.index.strtree.STRtree;
import org.springframework.stereotype.Service;

import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.IndexContainer;

@Service
public class SpatialIndexService {

  public IndexContainer buildSpatialIndices(Stream<Feature> inputStream) {
    var constraintsTree = new STRtree();
    var networksTree = new STRtree();
    var nodesTree = new STRtree();

    inputStream.forEachOrdered(feature -> {
      if (feature == null || feature.getGeometry() == null) {
        return;
      }

      var objectTypeObj = feature.getProperties().get("object_type");
      if (objectTypeObj == null) {
        return;
      }

      var objectType = objectTypeObj.toString();
      var envelope = feature.getGeometry().getEnvelopeInternal();

      switch (objectType) {
        case "restriction":
          constraintsTree.insert(envelope, feature);
          break;
        case "heat_network":
          networksTree.insert(envelope, feature);
          break;
        case "source":
        case "heat_chamber":
        case "oks_connection_point":
          nodesTree.insert(envelope, feature);
          break;
      }
    });

    // Фиксация индексов перед отдачей в алгоритмы трассировки
    constraintsTree.build();
    networksTree.build();
    nodesTree.build();

    return new IndexContainer(constraintsTree, networksTree, nodesTree);
  }
}
