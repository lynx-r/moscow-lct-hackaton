package com.ultra.dit.util;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.index.strtree.AbstractNode;
import org.locationtech.jts.index.strtree.Boundable;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.io.WKTWriter;

import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.FeatureProperties;

public class STRtreeVisualizer {

  public static void writeSTRtreeToFile(STRtree tree, String fileName) {
    var boundaries = extractTreeBoundaries(tree);
    Stream<Feature<? extends FeatureProperties>> features = boundaries.stream()
        .map((geo) -> Feature.of(geo, new FeatureProperties()));

    DebugUtils.writeFeaturesIn32637(features, fileName);
  }

  public static void printSTRtreeAsWKT(STRtree tree) {
    var boundaries = extractTreeBoundaries(tree);
    printGeometriesAsWKT(boundaries);
  }

  public static void printGeometriesAsWKT(List<Geometry> boundaries) {
    WKTWriter writer = new WKTWriter();
    writer.setFormatted(true);
    GeometryFactory gf = new GeometryFactory();

    // Объединяем все рамки узлов в одну коллекцию для удобства экспорта
    Geometry collection = gf.buildGeometry(boundaries);
    System.out.println("\n=== GEOMETRY WKT START ===");
    System.out.println(writer.write(collection));
    System.out.println("=== GEOMETRY WKT END ===\n");
    System.out.flush();

    // Полученную строку WKT можно напрямую вставить в QGIS (QuickWKT)
  }

  public static List<Geometry> extractTreeBoundaries(STRtree tree) {
    GeometryFactory gf = new GeometryFactory();
    List<Geometry> geometries = new ArrayList<>();

    // 2. Получаем корень дерева
    AbstractNode root = tree.getRoot();
    if (root != null) {
      // 3. Запускаем рекурсивный обход
      traverseNodes(root, geometries, gf);
    }

    return geometries;
  }

  private static void traverseNodes(AbstractNode node, List<Geometry> geometries, GeometryFactory gf) {
    // Добавляем границу текущего узла (внутреннего прямоугольника дерева)
    Envelope env = (Envelope) node.getBounds();
    if (env != null && !env.isNull()) {
      geometries.add(gf.toGeometry(env));
    }

    // Обходим всех детей этого узла
    List<?> childBoundables = node.getChildBoundables();
    for (Object child : childBoundables) {
      // Если ребенок является внутренним узлом дерева (AbstractNode) – идем глубже
      if (child instanceof AbstractNode) {
        traverseNodes((AbstractNode) child, geometries, gf);
      }
      // Если ребенок — это конечный элемент (листок/геометрия) – по желанию тоже
      // можно обработать
      else if (child instanceof Boundable) {
        Boundable b = (Boundable) child;
        Envelope leafEnv = (Envelope) b.getBounds();
        if (leafEnv != null && !leafEnv.isNull()) {
          // Уберите комментарий ниже, если хотите рисовать рамки самих объектов-листьев
          geometries.add(gf.toGeometry(leafEnv));
        }
      }
    }
  }
}
