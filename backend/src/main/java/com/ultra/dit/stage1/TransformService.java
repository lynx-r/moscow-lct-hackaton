package com.ultra.dit.stage1;

import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.ultra.dit.dto.Feature;
import com.ultra.dit.stage1.converter.GeometryCrsConverter;

import lombok.extern.java.Log;

@Log
@Service
public class TransformService {

  private final GeometryCrsConverter toUtmTransform;
  private final GeometryCrsConverter toWgsTransform;

  public TransformService() {
    this.toUtmTransform = new GeometryCrsConverter("EPSG:4326", "EPSG:32637");
    this.toWgsTransform = new GeometryCrsConverter("EPSG:32637", "EPSG:4326");
  }

  /**
   * Сервис для преобразования геометрии из WGS 84 (EPSG:4326) в EPSG:32637 (WGS
   * 84 / UTM zone 37N)
   * 
   * @param epsg4326Collection
   * @return
   * @throws Exception
   */
  public Stream<Feature> transform4326To32637(Stream<Feature> epsg4326Collection) {
    log.info("TransformService::transform4326To32637 start");
    var result = toUtmTransform.convert(epsg4326Collection);
    log.info("TransformService::transform4326To32637 finish");
    return result;
  }

  /**
   * Сервис для преобразования геометрии из EPSG:32637 (WGS 84 / UTM zone 37N) в
   * WGS 84 (EPSG:4326)
   * 
   * @param epsg32637Collection
   * @return
   * @throws Exception
   */
  public Stream<Feature> transform32637To4326(Stream<Feature> epsg32637Collection) {
    log.info("TransformService::transform32637To4326 start");
    var result = toWgsTransform.convert(epsg32637Collection);
    log.info("TransformService::transform32637To4326 finish");
    return result;
  }

}
