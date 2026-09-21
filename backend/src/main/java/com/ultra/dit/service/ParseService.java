package com.ultra.dit.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ultra.dit.dto.Feature;
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
   * @param inputStream
   * @return
   * @throws IOException
   */
  public Stream<Feature> streamFeatures(InputStream inputStream) {
    try {
      var factory = mapper.getFactory();
      var parser = factory.createParser(inputStream);

      boolean arrayFound = false;
      while (parser.nextToken() != null) {
        var currentName = parser.currentName();
        if ("features".equals(currentName) && parser.currentToken() == JsonToken.START_ARRAY) {
          arrayFound = true;
          break;
        }
      }

      if (!arrayFound) {
        throw new ParseException("В GeoJson не найден массив 'features'");
      }

      // КРИТИЧЕСКИЙ ШАГ: Сдвигаем парсер внутрь массива на первый элемент {
      parser.nextToken();

      MappingIterator<Feature> iterator = mapper.readerFor(Feature.class).readValues(parser);
      return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false)
          .onClose(() -> {
            try {
              parser.close();
            } catch (Exception e) {
              throw new ParseException("Ошибка парсинга GeoJson: " + e.getMessage());
            }
          });
    } catch (IOException e) {
      e.printStackTrace();
      throw new ParseException("Не удалось разобрать GeoJson " + e.getMessage());
    }
  }
}
