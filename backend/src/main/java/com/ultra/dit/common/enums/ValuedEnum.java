package com.ultra.dit.common.enums;

import java.util.Arrays;
import java.util.Optional;

public interface ValuedEnum<T> {
  // Метод, который должен реализовать каждый enum для возврата своего значения
  T getValue();

  // Универсальный статический метод для поиска константы по значению
  static <E extends Enum<E> & ValuedEnum<T>, T> Optional<E> fromValue(Class<E> enumClass, T value) {
    if (value == null) {
      return Optional.empty();
    }
    return Arrays.stream(enumClass.getEnumConstants())
        .filter(e -> value.equals(e.getValue()))
        .findFirst();
  }
}