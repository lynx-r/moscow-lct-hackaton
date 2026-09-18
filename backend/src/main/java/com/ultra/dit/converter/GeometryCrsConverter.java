package com.ultra.dit.converter;

import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

public class GeometryCrsConverter {

  private final CoordinateTransform transform;
  private final int SRID;

  public GeometryCrsConverter(String sourceCrsName, String targetCrsName) {
    this.SRID = Integer.valueOf(targetCrsName.split(":")[1].trim());

    var crsFactory = new CRSFactory();
    // Исходная система: WGS 84 (градусы)
    var sourceCrs = crsFactory.createFromName(sourceCrsName);
    // Целевая система: WGS 84 / UTM zone 37N (метры)
    var targetCrs = crsFactory.createFromName(targetCrsName);

    var transformFactory = new CoordinateTransformFactory();
    transform = transformFactory.createTransform(sourceCrs, targetCrs);
  }

  /**
   * Сервис для преобразования геометрии из CoordinateReferenceSystem
   * sourceCrsName в targetCrsName
   * 
   * @param inputCollection
   * @return
   * @throws Exception
   */
  public GeometryCollection convert(GeometryCollection inputCollection) {
    // Делаем копию, чтобы не менять исходный объект (good practice)
    var outputCollection = (GeometryCollection) inputCollection.copy();

    final ProjCoordinate srcCoord = new ProjCoordinate();
    final ProjCoordinate dstCoord = new ProjCoordinate();

    // Проходим по всем координатам внутри коллекции и меняем их на месте
    outputCollection.apply((CoordinateFilter) coordinate -> {
      srcCoord.x = coordinate.x;
      srcCoord.y = coordinate.y;

      transform.transform(srcCoord, dstCoord);

      coordinate.x = dstCoord.x;
      coordinate.y = dstCoord.y;
    });

    // Обновляем метаданные геометрии
    outputCollection.geometryChanged();
    outputCollection.setSRID(SRID);

    return outputCollection;
  }

}
