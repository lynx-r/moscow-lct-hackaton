package com.ultra.dit.stage2.config;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.bind.ServletRequestDataBinder;
import org.springframework.web.servlet.HandlerInterceptor;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class Stage2ParamInterceptor implements HandlerInterceptor {
  private final Stage2Properties properties;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    // 1. Создаем биндер для нашего @RequestScope бина свойств
    var binder = new ServletRequestDataBinder(properties);

    // 2. Биндим параметры запроса напрямую в объект.
    // Spring сам распарсит строки в int и double, сопоставив имена параметров с
    // сеттерами класса.
    binder.bind(request);

    return true;
  }
}
