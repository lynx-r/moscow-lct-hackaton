package com.ultra.dit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.FeatureCollection;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class GeoJsonService {
    private final ObjectMapper mapper;
    private final GeoJsonWriter writer;

    public GeoJsonService(ObjectMapper mapper){
        this.mapper = mapper;
        this.writer = new GeoJsonWriter();
    }
    public JsonNode toGeoJson(
            FeatureCollection featureCollection
    ) throws JsonProcessingException {
        ObjectNode root = mapper.createObjectNode();
        root.put(
                "type", "FeatureCollection"
        );

        ArrayNode features = mapper.createArrayNode();
        for (Feature feature : featureCollection.getFeatures()) {
            ObjectNode featureNode = mapper.createObjectNode();
            featureNode.put("type", "Feature");

            Geometry geometry = feature.getGeometry();
            JsonNode geometryNode = mapper.readTree(writer.write(geometry));

            featureNode.set("geometry", geometryNode);

            Map<String, Object> properties = feature.getProperties();
            JsonNode propertiesNode = mapper.valueToTree(properties);

            featureNode.set("properties", propertiesNode);
            features.add(featureNode);
        }
        root.set("features", features);
        return root;
    }

}
