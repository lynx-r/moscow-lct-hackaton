package com.ultra.dit.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ultra.dit.exception.ParseException;

@Service
public class ParseService {

  private final GeometryFactory geometryFactory;

  private final ObjectMapper mapper;

  ParseService(ObjectMapper mapper) {
    this.mapper = mapper;
    this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
  }

  /**
   * Парсит GeoJson строку с FeatureCollection в GeometryCollection
   * 
   * @param inputJsonGeometry
   * @return
   * @throws IOException
   */
  public GeometryCollection parseFeatureCollection(InputStream inputJsonGeometry) {
    try {
      // Читаем GeoJSON файл как дерево JSON-узлов
      var rootNode = mapper.readTree(inputJsonGeometry);
      var featuresNode = rootNode.get("features");

      var geometries = new ArrayList<Geometry>();

      // Обходим все фичи и вытаскиваем из них геометрии
      if (featuresNode != null && featuresNode.isArray()) {
        for (JsonNode feature : featuresNode) {
          JsonNode geometryNode = feature.get("geometry");
          if (geometryNode != null && !geometryNode.isNull()) {
            // Превращаем узел геометрии в объект JTS Geometry
            Geometry geom = mapper.treeToValue(geometryNode, Geometry.class);
            geom.setSRID(4326);
            geometries.add(geom);
          }
        }
      }

      // Создаем итоговую глобальную GeometryCollection
      var geomArray = geometries.toArray(new Geometry[0]);
      return geometryFactory.createGeometryCollection(geomArray);
    } catch (IOException e) {
      throw new ParseException("Не удалось разобрать GeoJson " + e.getMessage());
    }
  }
}
