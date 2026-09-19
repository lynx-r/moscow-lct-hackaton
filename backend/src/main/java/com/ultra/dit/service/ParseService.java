package com.ultra.dit.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.chrono.JapaneseDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.FeatureCollection;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ultra.dit.exception.ParseException;

@Service
public class ParseService {

  private static final int SOURCE_SRID = 4326;

  //private final GeometryFactory geometryFactory;

  private final ObjectMapper mapper;

  private final GeoJsonReader geoJsonReader;

  public ParseService(ObjectMapper mapper) {
    this.mapper = mapper;
    this.geoJsonReader = new GeoJsonReader();
    //this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
  }

  /**
   * Парсит GeoJson строку с FeatureCollection в GeometryCollection
   * 
   * @param inputStream
   * @return FeatureCollection
   * @throws ParseException
   */
  public FeatureCollection parse(InputStream inputStream) {
    try  {
      JsonNode rootNode = mapper.readTree(inputStream);

      validateFutureCollection(rootNode);

      List<Feature> features = new ArrayList<>();

      for (JsonNode featureNode: rootNode.get("features")) {
        Feature feature = parseFeature(featureNode);
        features.add(feature);
      }
      return new FeatureCollection(features);

    } catch (IOException e) {
      throw new ParseException("Не удалось разобрать GeoJSON: " + e.getMessage());
    }
  };

  public Feature parseFeature(JsonNode featureNode) {
    JsonNode geometryNode = featureNode.get("geometry");

    if (geometryNode == null || geometryNode.isNull()) {
      throw new ParseException("'Feature' ветка не содержит 'geometry'");
    }

    try {
      Geometry geometry = geoJsonReader.read(geometryNode.toString());
      geometry.setSRID(SOURCE_SRID);

      Map<String, Object> properties = new HashMap<>();

      JsonNode propertiesNode = featureNode.get("properties");

      if (propertiesNode != null && !propertiesNode.isNull()) {
        properties = mapper.convertValue(
                propertiesNode,
                Map.class
        );
      }

      return new Feature(geometry, properties);
    } catch (Exception e) {
      throw new ParseException("" + e.getMessage());
    }
  }


  public void validateFutureCollection (JsonNode rootNode) {
    if (rootNode==null || !rootNode.isObject()) {
      throw new ParseException("GeoJSON должен быть объектом JSON");
    }

    JsonNode typeNode = rootNode.get("type");

    if (typeNode == null || !"FeatureCollection".equals(typeNode.asText())) {
      throw new ParseException("Ожидается GeoJSON типа FeatureCollection");
    }

    JsonNode featureNode = rootNode.get("features");

    if (featureNode == null || !featureNode.isArray()) {
      throw new ParseException("FeatureCollection должен содержать массив 'features'");
    }
  }

  /*
  @Deprecated
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
  */
}
