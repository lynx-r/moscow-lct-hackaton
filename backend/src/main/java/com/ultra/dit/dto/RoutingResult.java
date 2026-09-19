package com.ultra.dit.dto;

import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Data
public class RoutingResult {
    /*
    * Пока что стандартный набор
    * в будущем добавить доп поля: distance, routes, cost...
     */
    @Getter
    public FeatureCollection featureCollection;
    // private Distance distance;
    public RoutingResult(FeatureCollection featureCollection) {
        this.featureCollection = featureCollection;
    }


}
