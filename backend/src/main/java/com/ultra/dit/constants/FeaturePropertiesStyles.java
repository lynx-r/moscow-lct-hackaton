package com.ultra.dit.constants;

import java.util.Map;

import com.ultra.dit.dto.geojson.StyleProperty;

public class FeaturePropertiesStyles {

        public static Map<String, String> SOURCE = Map.of(StyleProperty.MARKER_COLOR.getValue(),
                        "rgb(223, 20, 20)");

        public static Map<String, String> OKS_CONNECTION_POINT = Map.of(StyleProperty.MARKER_COLOR.getValue(),
                        "rgb(20, 223, 74)");

        public static Map<String, String> HEAT_NETWORK = Map.of(StyleProperty.STROKE.getValue(),
                        "rgb(223, 132, 20)");

        public static Map<String, String> HEAT_CHAMBER = Map.of(StyleProperty.MARKER_COLOR.getValue(),
                        "rgb(223, 213, 20)");

        public static Map<String, String> OKS_EXISTING = Map.of(StyleProperty.STROKE.getValue(),
                        "rgb(20, 47, 223)");

        public static Map<String, String> OKS_FUTURE = Map.of(StyleProperty.STROKE.getValue(),
                        "rgb(20, 179, 223)");

        public static Map<String, String> RESTRICTION = Map.of(StyleProperty.STROKE.getValue(),
                        "rgb(223, 20, 20)");

        public static Map<String, String> VORONOI_SKELETON = Map.of(StyleProperty.STROKE.getValue(),
                        "rgb(206, 20, 223)");

        public static Map<String, String> RESULT_STROKE = Map.of(StyleProperty.STROKE.getValue(),
                        "rgb(255, 0, 0)");
        public static Map<String, String> RESULT_POINT = Map.of(StyleProperty.STROKE.getValue(),
                        "rgb(234, 5, 255)");
}
