package com.ultra.dit.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.ultra.dit.common.constants.Api;
import com.ultra.dit.stage2.config.Stage2ParamInterceptor;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
  private final Stage2ParamInterceptor stage2ParamInterceptor;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(stage2ParamInterceptor).addPathPatterns(Api.API_ENDPOINT + "**");
  }

  // @Bean
  // public RequestContextListener requestContextListener() {
  // // Этот бин заставит Spring использовать InheritableThreadLocal внутри
  // // RequestContextHolder
  // // благодаря чему новые потоки унаследуют контекст HTTP-запроса
  // return new RequestContextListener();
  // }
}
