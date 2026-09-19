package com.ultra.dit.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoutingResult {
    /*
     * Пока что стандартный набор
     * в будущем добавить доп поля: distance, routes, cost...
     */
    private FeatureCollection featureCollection;
}
