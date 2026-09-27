package com.ultra.dit.exception;

import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.ultra.dit.dto.ErrorResponse;

@ControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(SolverException.class)
  public ResponseEntity<ErrorResponse> handleSolverException(SolverException ex) {
    return new ResponseEntity<>(new ErrorResponse(ex.getMessage(), ex.getStatus().value()), ex.getStatus());
  }


  /**
   * 1. Ошибки ПАРСИНГА (Встроенное исключение Spring)
   * Срабатывает, если клиент прислал невалидный JSON,
   * перепутал типы данных (например, строку вместо числа) или прислал пустой
   * запрос.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleParsingException(HttpMessageNotReadableException ex) {
    // Логируем для себя детальную ошибку
    // logger.error("Ошибка парсинга JSON", ex);

    String cleanMessage = "Неверный формат запроса (ошибка парсинга JSON).";

    ErrorResponse error = new ErrorResponse(cleanMessage, HttpStatus.BAD_REQUEST.value());
    return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST); // Возвращаем 400 Bad Request
  }

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<ErrorResponse> handleNoSuchElementException(NoSuchElementException ex) {
    // Логируем для себя детальную ошибку
    // logger.error("Ошибка парсинга JSON", ex);

    String cleanMessage = "Нет такого элемента";

    ErrorResponse error = new ErrorResponse(cleanMessage, HttpStatus.BAD_REQUEST.value());
    return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST); // Возвращаем 400 Bad Request
  }

  /**
   * Ошибки Парсинга
   */
  @ExceptionHandler(ParseException.class)
  public ResponseEntity<ErrorResponse> handleParseException(ParseException ex) {
    // Берем сообщение, которое вы передали при создании исключения
    ErrorResponse error = new ErrorResponse(ex.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY.value());

    // Обычно для ошибок бизнес-валидации/трансформации возвращают 422 код
    return new ResponseEntity<>(error, HttpStatus.UNPROCESSABLE_ENTITY);
  }

  /**
   * Ошибки трансформации
   */
  @ExceptionHandler(TransformException.class)
  public ResponseEntity<ErrorResponse> handleTransformException(TransformException ex) {
    // Берем сообщение, которое вы передали при создании исключения
    ErrorResponse error = new ErrorResponse(ex.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY.value());

    // Обычно для ошибок бизнес-валидации/трансформации возвращают 422 код
    return new ResponseEntity<>(error, HttpStatus.UNPROCESSABLE_ENTITY);
  }

  /**
   * Ошибки построения маршрутов
   */
  @ExceptionHandler(RoutingException.class)
  public ResponseEntity<ErrorResponse> handleRoutingException(RoutingException ex) {
    // Берем сообщение, которое вы передали при создании исключения
    ErrorResponse error = new ErrorResponse(ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR.value());

    // Обычно для ошибок бизнес-валидации/трансформации возвращают 422 код
    return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
  }

  /**
   * Ошибки построения индекса
   */
  @ExceptionHandler(SpatialIndexException.class)
  public ResponseEntity<ErrorResponse> handleSpatialIndexException(SpatialIndexException ex) {
    // Берем сообщение, которое вы передали при создании исключения
    ErrorResponse error = new ErrorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST.value());

    // Обычно для ошибок бизнес-валидации/трансформации возвращают 422 код
    return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
  }
}
