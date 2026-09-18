package com.ultra.dit.service;

import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.springframework.stereotype.Service;

@Service
public class TransformService {

  private final CoordinateTransform transform;

  public TransformService() {
    CRSFactory crsFactory = new CRSFactory();
    // 1. Настраиваем трансформацию координат через Proj4J
    // Исходная система: WGS 84 (градусы)
    CoordinateReferenceSystem sourceCRS = crsFactory.createFromName("EPSG:4326");
    // Целевая система: WGS 84 / UTM zone 37N (метры)
    CoordinateReferenceSystem targetCRS = crsFactory.createFromName("EPSG:32637");

    CoordinateTransformFactory transformFactory = new CoordinateTransformFactory();
    this.transform = transformFactory.createTransform(sourceCRS, targetCRS);
  }

  /**
   * Сервис для преобразования геометрии из WGS 84 (EPSG:4326) в EPSG:32637 (WGS
   * 84 / UTM zone 37N)
   * 
   * @param inputCollection
   * @return
   * @throws Exception
   */
  public GeometryCollection transformCollection(GeometryCollection inputCollection) {
    // Делаем копию, чтобы не менять исходный объект (good practice)
    var outputCollection = (GeometryCollection) inputCollection.copy();

    // Проходим по всем координатам внутри коллекции и меняем их на месте
    outputCollection.apply((CoordinateFilter) coordinate -> {
      ProjCoordinate srcCoord = new ProjCoordinate(coordinate.x, coordinate.y);
      ProjCoordinate dstCoord = new ProjCoordinate();

      transform.transform(srcCoord, dstCoord);

      coordinate.x = dstCoord.x;
      coordinate.y = dstCoord.y;
    });

    // Обновляем метаданные геометрии
    outputCollection.geometryChanged();
    outputCollection.setSRID(32637);

    // Проставляем SRID для всех вложенных геометрий
    for (int i = 0; i < outputCollection.getNumGeometries(); i++) {
      outputCollection.getGeometryN(i).setSRID(32637);
    }

    return outputCollection;
  }

}
