package com.ultra.dit.service;

import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.FeatureCollection;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.proj4j.*;
import org.springframework.stereotype.Service;

import com.ultra.dit.converter.GeometryCrsConverter;

import java.util.ArrayList;
import java.util.List;

@Service
public class TransformService {

  private static final int WGS84 = 4326;
  private static final int UTM_37M = 32637;

  private final CoordinateTransform toUtm;
  private final CoordinateTransform toWgs84;


  //private final GeometryCrsConverter toUtmTransform;
  //private final GeometryCrsConverter toWgsTransform;

  public TransformService() {
    CRSFactory crsFactory = new CRSFactory();
    CoordinateReferenceSystem wgs84 = crsFactory.createFromName("EPSG:4326");
    CoordinateReferenceSystem utm37n = crsFactory.createFromName("EPSG:32637");

    CoordinateTransformFactory transformFactory = new CoordinateTransformFactory();

    this.toUtm = transformFactory.createTransform(wgs84, utm37n);
    this.toWgs84 = transformFactory.createTransform(utm37n, wgs84);

//    this.toUtmTransform = new GeometryCrsConverter("EPSG:4326", "EPSG:32637");
//    this.toWgsTransform = new GeometryCrsConverter("EPSG:32637", "EPSG:4326");
  }

  /**
   * EPSG:4326 → EPSG:32637.
   */
  public FeatureCollection toUtm37n(FeatureCollection input) {
    return  transform(input, toUtm, UTM_37M);
  }

  /**
   * EPSG:32637 → EPSG:4326.
   */
  public FeatureCollection toWgs84(FeatureCollection input) {
    return  transform(input, toWgs84, WGS84);
  }

  private FeatureCollection transform(
          FeatureCollection input,
          CoordinateTransform coordinateTransform,
          int targetSrid
  ) {

    List<Feature> transformedFeatures = new ArrayList<>();

    for (Feature feature: input.getFeatures()) {
      Geometry geometry = feature.getGeometry().copy();

      geometry.apply(
              (CoordinateFilter) coordinate -> {
                      ProjCoordinate source = new ProjCoordinate(coordinate.x, coordinate.y);
                      ProjCoordinate target = new ProjCoordinate();
                      coordinateTransform.transform(source, target);
              }
      );

      geometry.geometryChanged();
      geometry.setSRID(targetSrid);

      transformedFeatures.add(new Feature(geometry, feature.getProperties()));
    }

    return new FeatureCollection(transformedFeatures);
  }

//  /**
//   * Сервис для преобразования геометрии из WGS 84 (EPSG:4326) в EPSG:32637 (WGS
//   * 84 / UTM zone 37N)
//   *
//   * @param epsg4326Collection
//   * @return
//   * @throws Exception
//   */
//  public GeometryCollection transform4326To32637(GeometryCollection epsg4326Collection) {
//    return toUtmTransform.convert(epsg4326Collection);
//  }
//
//  /**
//   * Сервис для преобразования геометрии из EPSG:32637 (WGS 84 / UTM zone 37N) в
//   * WGS 84 (EPSG:4326)
//   *
//   * @param epsg32637Collection
//   * @return
//   * @throws Exception
//   */
//  public GeometryCollection transform32637To4326(GeometryCollection epsg32637Collection) {
//    return toWgsTransform.convert(epsg32637Collection);
//  }

}
