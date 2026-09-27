package com.ultra.dit.common.constants;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ultra.dit.dto.geojson.enums.StyleProperty;

public class FeaturePropertiesStyles {

        public static Map<String, JsonNode> SOURCE = Map.of(StyleProperty.MARKER_COLOR.getValue(),
                        JsonNodeFactory.instance.textNode("rgb(223, 20, 20)"));

        public static Map<String, JsonNode> OKS_CONNECTION_POINT = Map.of(StyleProperty.MARKER_COLOR.getValue(),
                        JsonNodeFactory.instance.textNode("rgb(20, 223, 74)"));

        public static Map<String, JsonNode> HEAT_NETWORK = Map.of(StyleProperty.STROKE.getValue(),
                        JsonNodeFactory.instance.textNode("rgb(223, 132, 20)"));

        public static Map<String, JsonNode> HEAT_CHAMBER = Map.of(StyleProperty.MARKER_COLOR.getValue(),
                        JsonNodeFactory.instance.textNode("rgb(223, 213, 20)"));

        public static Map<String, JsonNode> OKS_EXISTING = Map.of(StyleProperty.STROKE.getValue(),
                        JsonNodeFactory.instance.textNode("rgb(20, 47, 223)"));

        public static Map<String, JsonNode> OKS_FUTURE = Map.of(StyleProperty.STROKE.getValue(),
                        JsonNodeFactory.instance.textNode("rgb(20, 179, 223)"));

        public static Map<String, JsonNode> RESTRICTION = Map.of(StyleProperty.STROKE.getValue(),
                        JsonNodeFactory.instance.textNode("rgb(223, 20, 20)"));

        public static Map<String, JsonNode> VORONOI_SKELETON = Map.of(StyleProperty.STROKE.getValue(),
                        JsonNodeFactory.instance.textNode("rgb(206, 20, 223)"));

        public static Map<String, JsonNode> RESULT_STROKE = Map.of(StyleProperty.STROKE.getValue(),
                        JsonNodeFactory.instance.textNode("rgb(255, 0, 0)"));
        public static Map<String, JsonNode> RESULT_POINT = Map.of(StyleProperty.STROKE.getValue(),
                        JsonNodeFactory.instance.textNode("rgb(234, 5, 255)"));
}
