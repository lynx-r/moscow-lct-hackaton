import type { Stage2Properties } from '@/types/Stage2Properties'

export const STAGE2_PROPERTIES_DEFAULT: Stage2Properties = {
  //Вычисляет общий минимальный охватывающий прямоугольник (Envelope)
  //для кластера ОКС с учетом технологического буфера
  clusterBufferMeters: 500.0,
  //Пессимистичный радиус буфера для максимального ДУ 1400 мм по ТЗ (в метрах)
  bufferRadiusMeters: 2.0,
  //Размер кластера
  clusterSizeMeters: 10.0,
  //N метра буфера для точки подключения на границе здания
  oksConnectionPointBufferMeters: 2.0,
  //Количество вариантов для генерации
  maxVariants: 3,
  //Максимальная степень узла для камер по ТЗ
  maxValence: 4,
  //из скольки кратчайших путей выбирать
  chooseFromPathsCount: 5,
  //Ограничиваемся 3 параллельными кластерами
  // parallelClusterCount: 3,
  //Критерий уникальности: если более 70% вершин совпадают, считаем варианты идентичными
  pathUniquePercent: 0.7,
  //5 ближайших точек врезки при построении сети кластера
  closesSourcesCount: 5,
}

export const STAGE2_PROPERTIES_FAST: Stage2Properties = {
  ...STAGE2_PROPERTIES_DEFAULT,
  maxVariants: 1,
  chooseFromPathsCount: 1,
}
