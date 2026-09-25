package com.ultra.dit.aop;

import java.util.stream.Stream;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import lombok.extern.java.Log;

@Log
@Component
@Aspect
public class LoggingAspect {

  @Around("@annotation(LogExecutionTime)")
  public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
    long start = System.currentTimeMillis();

    Object proceed = joinPoint.proceed();

    if (proceed instanceof Stream) {
      // Добавляем хук на закрытие стрима (в контроллере нужно будет использовать
      // try-with-resources)
      return ((Stream<?>) proceed).onClose(() -> {
        long executionTime = System.currentTimeMillis() - start;
        log.info(joinPoint.getSignature() + " (стрим закрыт) выполнен за " + executionTime + "мс");
      });
    }

    long executionTime = System.currentTimeMillis() - start;

    log.info(joinPoint.getSignature() + " выполнен за " + executionTime + "мс");
    return proceed;
  }
}