package com.ultra.dit.service;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ultra.dit.dto.FeatureCollection;
import com.ultra.dit.exception.ParseException;

@Service
public class ParseService {

  private final ObjectMapper mapper;

  ParseService(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * Парсит GeoJson строку с FeatureCollection в GeometryCollection
   * 
   * @param inputJsonGeometry
   * @return
   * @throws IOException
   */
  public FeatureCollection parseFeatureCollection(InputStream inputJsonGeometry) {
    try {
      return mapper.readValue(inputJsonGeometry, FeatureCollection.class);
    } catch (IOException e) {
      e.printStackTrace();
      throw new ParseException("Не удалось разобрать GeoJson " + e.getMessage());
    }
  }
}
