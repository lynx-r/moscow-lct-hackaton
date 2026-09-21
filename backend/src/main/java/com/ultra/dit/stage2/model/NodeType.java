package com.ultra.dit.stage2.model;

// Тип узла в ориентированном каркасе
public enum NodeType {
  EXISTING_NETWORK_NODE, // Точка врезки / существующая камера
  VORONOI_SKELETON, // Опорная точка безопасного коридора
  OKS_CONNECTION, // Целевая точка подключения ОКС
  NEW_CHAMBER // Новая камера (точка слияния / Steiner node)
}
