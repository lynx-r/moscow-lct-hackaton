package com.ultra.dit.service;

import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;

import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.graph.AsSubgraph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.jgrapht.nio.graphml.GraphMLExporter;
import org.n52.jackson.datatype.jts.JtsModule;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ultra.dit.dto.FeatureCollection;
import com.ultra.dit.stage2.model.VirtualNode;

public class DebugUtils {
  private static final ObjectMapper OBJECT_MAPPER = new Jackson2ObjectMapperBuilder().failOnUnknownProperties(false)
      .indentOutput(true)
      .modules(new JtsModule()).build();

  public static void writeFeatureCollectionToFile(FeatureCollection featureCollection, Path path) {
    String fileNamePath = path.toString();
    try (var outputStream = new FileOutputStream(fileNamePath)) {
      OBJECT_MAPPER.writeValue(outputStream, featureCollection);
    } catch (IOException e) {
      System.out.println(e);
    }
  }

  public static <V, E> void exportToGraphML(Graph<V, E> graph,
      String fileName) {
    GraphMLExporter<V, E> exporter = new GraphMLExporter<>();

    // Включаем экспорт весов ребер
    exporter.setExportEdgeWeights(true);
    // Опционально: настраиваем ID для вершин, чтобы GraphML корректно их читал
    // exporter.setVertexIdProvider(v -> String.valueOf(v.hashCode()));

    var path = Paths.get("data", "debug", "grapht", fileName).toString();
    try (var writer = new FileWriter(path)) {
      exporter.exportGraph(graph, writer);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public static <V, E> void exportPathToGraphML(GraphPath<V, E> graphPath, String filePath) {
    // 1. Получаем исходный граф, к которому принадлежит путь
    Graph<V, E> baseGraph = graphPath.getGraph();

    // 2. Выделяем все вершины и ребра, которые входят в путь, в множества
    // Используем HashSet для быстрой фильтрации
    var pathVertices = new HashSet<>(graphPath.getVertexList());
    var pathEdges = new HashSet<>(graphPath.getEdgeList());

    // 3. Создаем представление графа (подграф), содержащее только элементы пути
    Graph<V, E> pathGraph = new AsSubgraph<>(baseGraph, pathVertices, pathEdges);

    exportToGraphML(pathGraph, filePath);
  }
}
