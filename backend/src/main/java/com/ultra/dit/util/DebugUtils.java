package com.ultra.dit.util;

import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ultra.dit.config.DebugConfig;
import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.FeatureCollection;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.stage1.TransformService;
import com.ultra.dit.stage2.model.VirtualNode;

@Component
public class DebugUtils {

  public static Path DEFAULT_DEBUG_FOLDER = Paths.get("debug");
  public static Path GEOJSON_FOLDER = Paths.get("geojson");
  public static Path GRPAHML_FOLDER = Paths.get("grapht");

  public static final String GEOJSON_EXT = ".geojson";
  public static final String GRAPHT_EXT = ".graphml";

  private static final TransformService transform = new TransformService();

  private static final GeometryFactory geometryFactory = new GeometryFactory();

  public static final ObjectMapper OBJECT_MAPPER = new Jackson2ObjectMapperBuilder().failOnUnknownProperties(false)
      .indentOutput(true)
      .serializationInclusion(JsonInclude.Include.NON_EMPTY)
      .modules(new JtsModule()).build();

  // Статическое поле, куда Spring подставит нужный бин (Dev или Prod)
  private static DebugConfig debugConfig;

  private static Path debugFolder;

  // Внедряем интерфейс через конструктор компонента и сохраняем в статическое
  // поле
  public DebugUtils(DebugConfig debugConfig) {
    DebugUtils.debugConfig = debugConfig;

    DebugUtils.debugFolder = debugConfig.getDebugFolder();
    initDebugFolders();
  }

  /** Debug Features Start */

  public static <T extends FeatureProperties> void writeFeaturesIn32637(
      Stream<Feature<? extends FeatureProperties>> featureStream,
      String fileName) {
    if (isDisabled()) {
      return;
    }
    // String fileNamePath32637 = GEOJSON_FOLDER.resolve(fileName +
    // "-32637").toString();

    var rawFeatures = featureStream.collect(Collectors.toList());
    // writeFeaturesToFile(rawFeatures, fileNamePath32637);

    String fileNamePath4326 = getGeoJsonFolder().resolve(fileName + "-4326").toString();
    var features = transform.transform32637To4326(rawFeatures.stream()).collect(Collectors.toList());
    writeFeaturesToFile(features, fileNamePath4326);
  }

  public static void writeFeatures(Stream<Feature<? extends FeatureProperties>> featureStream, String fileName) {
    if (isDisabled()) {
      return;
    }

    String fileNamePath = getGeoJsonFolder().resolve(fileName).toString();
    var rawFeatures = featureStream.collect(Collectors.toList());
    writeFeaturesToFile(rawFeatures, fileNamePath);
  }

  private static void writeFeaturesToFile(
      List<Feature<? extends FeatureProperties>> featureStream,
      String fileName) {
    var rawFeatureCollection = FeatureCollection.of(featureStream);
    var fileNameExt = fileName + GEOJSON_EXT;
    try (var outputStream = new FileOutputStream(fileNameExt)) {
      DebugUtils.OBJECT_MAPPER.writeValue(outputStream, rawFeatureCollection);
    } catch (IOException e) {
      throw new RuntimeException("Ошибка записи GeoJSON в файл: " + fileName, e);
    }
  }

  // Публичное статическое свойство (опционально, если нужно именно поле)
  private static Path getGeoJsonFolder() {
    return debugConfig != null ? debugFolder.resolve(GEOJSON_FOLDER)
        : DEFAULT_DEBUG_FOLDER.resolve(GEOJSON_FOLDER);
  }

  /** Debug Features End */

  /** Debug Graph Start */

  public static <V extends VirtualNode, E> void writeGraph(Graph<V, E> graph,
      String fileName) {
    if (isDisabled()) {
      return;
    }

    writeGraphWithFeatures(graph, Stream.empty(), fileName);

    exportToGraphML(graph, fileName);
  }

  public static <V extends VirtualNode, E> void writeGraphPaths(List<GraphPath<V, E>> paths, String fileName) {
    if (isDisabled()) {
      return;
    }

    var pathId = new AtomicInteger(0);
    paths.forEach((path) -> {
      exportPathToGraphML(path, fileName + "-path-" + pathId.incrementAndGet());
    });
  }

  private static <V extends VirtualNode, E> void writeGraphWithFeatures(Graph<V, E> graph,
      Stream<Feature<? extends FeatureProperties>> featureStream,
      String fileName) {

    var graphEdgeFeatures = StreamSupport.stream(graph.iterables().edges().spliterator(), false).map(edge -> {
      VirtualNode from = graph.getEdgeSource(edge);
      VirtualNode to = graph.getEdgeTarget(edge);

      var lineCoords = new Coordinate[] {
          from.getCoordinate(),
          to.getCoordinate()
      };
      var lineString = geometryFactory.createLineString(lineCoords);

      FeatureProperties properties;
      if (from.hasFeatureProperties()) {
        properties = from.getFeatureProperties();
      } else if (to.hasFeatureProperties()) {
        properties = to.getFeatureProperties();
      } else {
        properties = new FeatureProperties();
      }

      return Feature.of(lineString, properties);
    });

    var allFeatures = Stream.concat(graphEdgeFeatures, featureStream);
    writeFeaturesIn32637(allFeatures, fileName);
  }

  private static <V, E> void exportToGraphML(Graph<V, E> graph,
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

    var path = getGraphMLFolder().resolve(fileName + GRAPHT_EXT).toString();
    try (var writer = new FileWriter(path)) {
      exporter.exportGraph(graph, writer);
    } catch (IOException e) {
      throw new RuntimeException("Ошибка экспорта графа " + fileName, e);
    }
  }

  private static <V extends VirtualNode, E> void exportPathToGraphML(GraphPath<V, E> graphPath, String filePath) {
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

  // Публичное статическое свойство (опционально, если нужно именно поле)
  private static Path getGraphMLFolder() {
    return debugConfig != null ? debugFolder.resolve(GRPAHML_FOLDER)
        : DEFAULT_DEBUG_FOLDER.resolve(GRPAHML_FOLDER);
  }

  /** Debug Graph Start */

  private static boolean isDisabled() {
    return debugConfig == null || debugConfig.getDisabled();
  }

  private void initDebugFolders() {
    if (isDisabled()) {
      return;
    }

    try {
      FileSystemUtils.deleteRecursively(getGeoJsonFolder());
      FileSystemUtils.deleteRecursively(getGeoJsonFolder());

      createDirectoryIfNotExist(debugFolder);
      createDirectoryIfNotExist(getGeoJsonFolder());
      createDirectoryIfNotExist(getGraphMLFolder());

    } catch (IOException e) {
      throw new RuntimeException("Не удалось создать директорию", e);
    }
  }

  public void createDirectoryIfNotExist(Path path) throws IOException {
    if (Files.notExists(path)) {
      // Создает всю цепочку папок, если они не существуют.
      // Если папка уже есть — ничего не произойдет.
      Files.createDirectory(path);
    }
  }
}
