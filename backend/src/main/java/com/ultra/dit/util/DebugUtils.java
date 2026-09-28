package com.ultra.dit.util;

import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import com.ultra.dit.config.DebugProperties;
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

  private static final String DEBUG_SESSION_FILENAMES_FILENAME = "debug-session-filenames.json";

  private static final TransformService transform = new TransformService();

  private static final GeometryFactory geometryFactory = new GeometryFactory();

  public static final ObjectMapper OBJECT_MAPPER = new Jackson2ObjectMapperBuilder().failOnUnknownProperties(false)
      .indentOutput(true)
      .serializationInclusion(JsonInclude.Include.NON_EMPTY)
      .modules(new JtsModule()).build();

  // Статическое поле, куда Spring подставит нужный бин (Dev или Prod)
  private static DebugProperties debugConfig;

  private static Path debugFolder;

  // Хранилище списков имен файлов для каждого активного запроса.
  // Ключ — это уникальное имя сессии, которое мы привяжем к именам потоков.
  private static final ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> activeSessions = new ConcurrentHashMap<>();

  // ThreadLocal нужен только главному потоку, чтобы в конце знать, какую сессию
  // закрывать
  private static final ThreadLocal<String> currentSessionId = new ThreadLocal<>();

  // Внедряем интерфейс через конструктор компонента и сохраняем в статическое
  // поле
  public DebugUtils(DebugProperties debugConfig) {
    DebugUtils.debugConfig = debugConfig;

    DebugUtils.debugFolder = debugConfig.getDebugFolder();
  }

  /** Written file names Session Start */

  /**
   * Стартуем сессию. Создаем пустой потокобезопасный список в памяти.
   */
  public static void startSession(String sessionId) {
    if (isDisabled()) {
      return;
    }
    initDebugFolders();

    activeSessions.put(sessionId, new ConcurrentLinkedQueue<>());
    currentSessionId.set(sessionId);
  }

  /**
   * Потокобезопасное добавление имени файла из ЛЮБОГО потока ForkJoinPool
   */
  private static void logFileName(String fileName) {
    if (isDisabled()) {
      return;
    }
    String threadName = Thread.currentThread().getName();

    // Ищем, к какому запросу принадлежит текущий воркер-поток
    for (String sessionId : activeSessions.keySet()) {
      if (threadName.contains(sessionId)) {
        ConcurrentLinkedQueue<String> fileNames = activeSessions.get(sessionId);
        if (fileNames != null) {
          fileNames.add(fileName); // Операция lock-free, максимально быстрая
        }
        break;
      }
    }
  }

  /**
   * Завершаем сессию: главный поток берет весь накопленный список,
   * один раз открывает файл, записывает всё и очищает память.
   */
  public static void endSession() throws IOException {
    if (isDisabled()) {
      return;
    }

    var sessionId = currentSessionId.get();
    if (sessionId == null) {
      return;
    }

    var debugFileName = getDebugFolder().resolve(DEBUG_SESSION_FILENAMES_FILENAME);
    System.out.println(debugFileName);
    try {
      ConcurrentLinkedQueue<String> fileNames = activeSessions.remove(sessionId);
      System.out.println(fileNames.size());
      if (fileNames != null && !fileNames.isEmpty()) {

        var staticDebugFilesUrl = debugConfig.getStaticDebugFilesUrl();
        var fileUrls = fileNames.stream()
            .map(n -> n.replaceAll(getDebugFolder().toString(), staticDebugFilesUrl))
            .collect(Collectors.toList());

        // Открываем файл на перезапись (TRUNCATE_EXISTING)
        try (var writer = Files.newBufferedWriter(debugFileName,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING)) {

          OBJECT_MAPPER.writeValue(writer, fileUrls);
        }
      }
    } finally {
      currentSessionId.remove();
    }
  }

  // Публичное статическое свойство (опционально, если нужно именно поле)
  private static Path getDebugFolder() {
    return debugFolder != null ? debugFolder : DEFAULT_DEBUG_FOLDER;
  }

  /** Written file names Session End */

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

      // Просто добавляет строку в ConcurrentLinkedQueue в памяти.
      // Никаких блокировок диска, никакой задержки потоков.
      DebugUtils.logFileName(fileNameExt);
    } catch (IOException e) {
      throw new RuntimeException("Ошибка записи GeoJSON в файл: " + fileName, e);
    }
  }

  // Публичное статическое свойство (опционально, если нужно именно поле)
  private static Path getGeoJsonFolder() {
    return getDebugFolder().resolve(GEOJSON_FOLDER);
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

    Stream<Feature<? extends FeatureProperties>> graphEdgeFeatures = StreamSupport
        .stream(graph.iterables().edges().spliterator(), false).map(edge -> {
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

    String fileNameExt = fileName + GRAPHT_EXT;
    var path = getGraphMLFolder().resolve(fileNameExt).toString();
    try (var writer = new FileWriter(path)) {
      exporter.exportGraph(graph, writer);

      DebugUtils.logFileName(path);
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
  }

  // Публичное статическое свойство (опционально, если нужно именно поле)
  private static Path getGraphMLFolder() {
    return getDebugFolder().resolve(GRPAHML_FOLDER);
  }

  /** Debug Graph Start */

  private static boolean isDisabled() {
    return debugConfig == null || debugConfig.getDisabled() || debugFolder == null;
  }

  private static void initDebugFolders() {
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

  private static void createDirectoryIfNotExist(Path path) throws IOException {
    if (Files.notExists(path)) {
      // Создает всю цепочку папок, если они не существуют.
      // Если папка уже есть — ничего не произойдет.
      Files.createDirectory(path);
    }
  }
}
