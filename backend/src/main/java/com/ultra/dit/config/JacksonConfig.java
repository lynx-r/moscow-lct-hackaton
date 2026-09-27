package com.ultra.dit.config;

import org.n52.jackson.datatype.jts.JtsModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

@Configuration
public class JacksonConfig {

  @Bean
  public Jackson2ObjectMapperBuilder configureObjectMapper() {
    var objectMapper = new Jackson2ObjectMapperBuilder();
    objectMapper.failOnUnknownProperties(false);
    // Default JTS serialization rounds to 8 decimals, changing lengths and costs.
    objectMapper.modules(new JtsModule(16));

    return objectMapper;
  }
}
