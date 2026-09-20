package com.ultra.dit.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ultra.dit.dto.Feature;
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
   * @param inputStream
   * @return
   * @throws IOException
   */
  public Stream<Feature> parseFeatureCollection(InputStream inputStream) {
    try {
      var factory = mapper.getFactory();
      var parser = factory.createParser(inputStream);

      while (parser.nextToken() != null) {
        var currentName = parser.currentName();
        if ("features".equals(currentName) && parser.currentToken() == JsonToken.START_ARRAY) {
          break;
        }
      }

      var iterator = new Iterator<Feature>() {

        @Override
        public boolean hasNext() {
          try {
            var hasNext = parser.nextToken() != JsonToken.END_ARRAY;
            if (!hasNext) {
              parser.close();
            }
            return hasNext;
          } catch (Exception e) {
            try {
              parser.close();
            } catch (Exception ignored) {
            }
            throw new ParseException("Ошибка стриминга GeoJSON: " + e.getMessage());
          }
        }

        @Override
        public Feature next() {
          try {
            return mapper.readValue(parser, Feature.class);
          } catch (IOException e) {
            throw new ParseException("Ошибка стриминга GeoJSON: " + e.getMessage());
          }
        }
      };

      return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false);
    } catch (IOException e) {
      e.printStackTrace();
      throw new ParseException("Не удалось разобрать GeoJson " + e.getMessage());
    }
  }
}
