package com.ultra.dit.service;

import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.graph.AsSubgraph;
import org.jgrapht.nio.graphml.GraphMLExporter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.n52.jackson.datatype.jts.JtsModule;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.util.FileSystemUtils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.FeatureCollection;
import com.ultra.dit.stage1.TransformService;
import com.ultra.dit.stage2.model.VirtualNode;

public class DebugUtils {
  public static Path DEBUG_FOLDER = Paths.get("debug");
  public static Path GEOJSON_FOLDER = DEBUG_FOLDER.resolve("geojson");
  public static Path GRPAHT_FOLDER = DEBUG_FOLDER.resolve("grapht");
  public static Path TEMP_FOLDER = DEBUG_FOLDER.resolve("temp");

  private static final String GEOJSON_EXT = ".geojson";
  private static final String GRAPHT_EXT = ".graphml";

  private static final TransformService transform = new TransformService();

  private static final GeometryFactory geometryFactory = new GeometryFactory();

  static {
    try {
      FileSystemUtils.deleteRecursively(DebugUtils.DEBUG_FOLDER);

      if (Files.notExists(DEBUG_FOLDER)) {
        // Создает всю цепочку папок, если они не существуют.
        // Если папка уже есть — ничего не произойдет.
        Files.createDirectory(DEBUG_FOLDER);
        Files.createDirectory(GEOJSON_FOLDER);
        Files.createDirectory(GRPAHT_FOLDER);
        Files.createDirectory(TEMP_FOLDER);
        System.out.println("Директории готовы к использованию!");
      }
    } catch (IOException e) {
      System.err.println("Не удалось создать директорию: " + e.getMessage());
    }
  }

  public static final ObjectMapper OBJECT_MAPPER = new Jackson2ObjectMapperBuilder().failOnUnknownProperties(false)
      .indentOutput(true)
      .serializationInclusion(JsonInclude.Include.NON_EMPTY)
      .modules(new JtsModule()).build();

  public static void writeFeaturesIn32637(Stream<Feature> featureStream, String fileName) {
    String fileNamePath32637 = GEOJSON_FOLDER.resolve("32637:" + fileName).toString();

    var rawFeatures = featureStream.collect(Collectors.toList());
    writeFeaturesToFile(rawFeatures, fileNamePath32637);

    String fileNamePath4326 = GEOJSON_FOLDER.resolve("4326:" + fileName).toString();
    var features = transform.transform32637To4326(rawFeatures.stream()).collect(Collectors.toList());
    writeFeaturesToFile(features, fileNamePath4326);
  }

  public static void writeFeatures(Stream<Feature> featureStream, String fileName) {
    String fileNamePath32637 = GEOJSON_FOLDER.resolve(fileName).toString();

    var rawFeatures = featureStream.collect(Collectors.toList());
    writeFeaturesToFile(rawFeatures, fileNamePath32637);
  }

  public static void writeFeaturesToFile(List<Feature> featureStream, String fileName) {
    var rawFeatureCollection = FeatureCollection.of(featureStream);
    try (var outputStream = new FileOutputStream(fileName + GEOJSON_EXT)) {
      OBJECT_MAPPER.writeValue(outputStream, rawFeatureCollection);
    } catch (IOException e) {
      System.out.println(e);
    }
  }

  public static <V extends VirtualNode, E> void writeGraphWithFeatures(Graph<V, E> graph, Stream<Feature> featureStream,
      String fileName) {

    var graphEdgeFeatures = StreamSupport.stream(graph.iterables().edges().spliterator(), false).map(edge -> {
      VirtualNode from = graph.getEdgeSource(edge);
      VirtualNode to = graph.getEdgeTarget(edge);

      var lineCoords = new Coordinate[] {
          from.getCoordinate(),
          to.getCoordinate()
      };
      var lineString = geometryFactory.createLineString(lineCoords);
      return Feature.withDefaultProperties(lineString);
    });

    var allFeatures = Stream.concat(graphEdgeFeatures, featureStream);
    writeFeaturesIn32637(allFeatures, fileName);
  }

  public static <V extends VirtualNode, E> void writeGraph(Graph<V, E> graph,
      String fileName) {

    writeGraphWithFeatures(graph, Stream.empty(), fileName);

    exportToGraphML(graph, fileName);
  }

  public static <V, E> void exportToGraphML(Graph<V, E> graph,
      String fileName) {
    GraphMLExporter<V, E> exporter = new GraphMLExporter<>();

    // Включаем экспорт весов ребер
    exporter.setExportEdgeWeights(true);
    // Опционально: настраиваем ID для вершин, чтобы GraphML корректно их читал
    // Вместо дефолтных номеров заставим экспортер писать в ID координаты узла,
    // чтобы сразу видеть их в XML
    exporter.setVertexIdProvider(v -> {
      // Преобразуем координаты в строку без пробелов и точек, пригодную для XML ID
      // (например, "N_24_7")
      return "N_" + (((VirtualNode) v).getCoordinate().x) + "_" + (((VirtualNode) v).getCoordinate().y);
    });

    var path = GRPAHT_FOLDER.resolve(fileName + GRAPHT_EXT).toString();
    try (var writer = new FileWriter(path)) {
      exporter.exportGraph(graph, writer);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public static <V extends VirtualNode, E> void writeGraphPaths(List<GraphPath<V, E>> paths, String fileName) {
    paths.forEach((path) -> {
      exportPathToGraphML(path, fileName);
    });
  }

  public static <V extends VirtualNode, E> void exportPathToGraphML(GraphPath<V, E> graphPath, String filePath) {
    // 1. Получаем исходный граф, к которому принадлежит путь
    Graph<V, E> baseGraph = graphPath.getGraph();

    // 2. Выделяем все вершины и ребра, которые входят в путь, в множества
    // Используем HashSet для быстрой фильтрации
    var pathVertices = new HashSet<>(graphPath.getVertexList());
    var pathEdges = new HashSet<>(graphPath.getEdgeList());

    // 3. Создаем представление графа (подграф), содержащее только элементы пути
    Graph<V, E> pathGraph = new AsSubgraph<>(baseGraph, pathVertices, pathEdges);

    writeGraph(pathGraph, filePath);
    exportToGraphML(pathGraph, filePath);
  }
}
