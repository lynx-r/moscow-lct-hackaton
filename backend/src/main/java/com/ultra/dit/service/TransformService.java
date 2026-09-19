package com.ultra.dit.service;

import javax.annotation.PreDestroy;

import org.springframework.stereotype.Service;

import com.ultra.dit.converter.GeometryCrsConverter;
import com.ultra.dit.dto.FeatureCollection;

@Service
public class TransformService {

  private final GeometryCrsConverter toUtmTransform;
  private final GeometryCrsConverter toWgsTransform;

  public TransformService() {
    this.toUtmTransform = new GeometryCrsConverter("EPSG:4326", "EPSG:32637");
    this.toWgsTransform = new GeometryCrsConverter("EPSG:32637", "EPSG:4326");
  }

  @PreDestroy
  public void shutdown() {
    toUtmTransform.close();
    toWgsTransform.close();
  }

  /**
   * Сервис для преобразования геометрии из WGS 84 (EPSG:4326) в EPSG:32637 (WGS
   * 84 / UTM zone 37N)
   * 
   * @param epsg4326Collection
   * @return
   * @throws Exception
   */
  public FeatureCollection transform4326To32637(FeatureCollection epsg4326Collection) {
    return toUtmTransform.convert(epsg4326Collection);
  }

  /**
   * Сервис для преобразования геометрии из EPSG:32637 (WGS 84 / UTM zone 37N) в
   * WGS 84 (EPSG:4326)
   * 
   * @param epsg32637Collection
   * @return
   * @throws Exception
   */
  public FeatureCollection transform32637To4326(FeatureCollection epsg32637Collection) {
    return toWgsTransform.convert(epsg32637Collection);
  }

}
