package com.ultra.dit.config;

import org.n52.jackson.datatype.jts.JtsModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

  @Bean
  JtsModule jtsModule() {
    return new JtsModule();
  }
}
