package com.ultra.dit.stage1;

import java.io.IOException;
import java.io.InputStream;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ultra.dit.aop.LogExecutionTime;
import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.exception.ParseException;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
@RequiredArgsConstructor
public class ParseService {

  private final ObjectMapper mapper;

  /**
   * Парсит GeoJson строку с FeatureCollection в GeometryCollection
   * 
   * @param inputStream
   * @return
   * @throws IOException
   */
  @LogExecutionTime
  public Stream<Feature<? extends FeatureProperties>> streamFeatures(InputStream inputStream) {
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

      ObjectMapper mapper = (ObjectMapper) parser.getCodec();

      // Конструируем тип Feature<FeatureProperties>
      var featureType = mapper.getTypeFactory()
          .constructParametricType(Feature.class, FeatureProperties.class);

      // Итератор будет возвращать базовый вид Feature<FeatureProperties>
      MappingIterator<Feature<? extends FeatureProperties>> iterator = mapper.readerFor(featureType).readValues(parser);
      return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false)
          .onClose(() -> {
            try {
              parser.close();
            } catch (Exception e) {
              log.error(e);
              throw new ParseException("Ошибка парсинга GeoJson: " + e.getMessage());
            }
          });
    } catch (IOException e) {
      log.error(e);
      throw new ParseException("Не удалось разобрать GeoJson " + e.getMessage());
    }
  }
}
