<!-- MapViewer.vue -->
<script setup>
import * as maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { onBeforeUnmount, onMounted, shallowRef, watch } from 'vue'

const props = defineProps({
  // Получаем GeoJSON с вариантами трассировок от бэкенда
  routingData: { type: Object, default: null },
  // ID выбранного пользователем варианта (Опция 1, Опция 2 и т.д.)
  selectedOptionId: { type: [String, Number], default: null },
})

const mapContainer = shallowRef(null)
const map = shallowRef(null) // Важно: shallowRef, чтобы не ломать WebGL-контекст реактивностью

// Инициализация карты
onMounted(() => {
  map.value = new maplibregl.Map({
    container: mapContainer.value,
    style: 'https://cartocdn.com', // Темная подложка идеальна для инженерных сетей
    center: [37.6173, 55.7558], // Центрируем на нужный регион
    zoom: 13,
  })

  map.value.on('load', () => {
    initInfrastructureLayers()
    updateRoutingLayers(props.routingData)
  })
})

// Инициализация базовых слоев (дороги, существующие сети, запретные зоны)
function initInfrastructureLayers() {
  if (!map.value) return

  // Пример: Источник существующих теплосетей (берем из вашего ГИС-бэка)
  map.value.addSource('existing-heat-networks', {
    type: 'geojson',
    data: '/api/geojson/existing-infrastructure', // Или пустой GeoJSON для последующей загрузки
  })

  // Отрисовка существующих труб серым цветом
  map.value.addLayer({
    id: 'existing-pipes-line',
    type: 'line',
    source: 'existing-heat-networks',
    paint: {
      'line-color': '#4a4a4a',
      'line-width': 2,
      'line-dasharray': [2, 2],
    },
  })
}

// Обновление слоев при расчете новой трассировки
function updateRoutingLayers(geoJsonData) {
  if (!map.value || !geoJsonData) return

  // Динамически обновляем или добавляем источник данных предложенных маршрутов
  if (map.value.getSource('proposed-routes')) {
    map.value.getSource('proposed-routes').setData(geoJsonData)
  } else {
    map.value.addSource('proposed-routes', { type: 'geojson', data: geoJsonData })

    // Слой для отрисовки новых труб (например, красным/оранжевым цветом)
    map.value.addLayer({
      id: 'proposed-pipes-line',
      type: 'line',
      source: 'proposed-routes',
      paint: {
        'line-color': [
          'case',
          ['boolean', ['feature-state', 'selected'], false],
          '#ff4d4f',
          '#ffa940',
        ],
        'line-width': ['case', ['boolean', ['feature-state', 'selected'], false], 5, 3],
      },
    })

    // Слой для новых тепловых камер (круглые маркеры на изгибах)
    map.value.addLayer({
      id: 'proposed-chambers-circle',
      type: 'circle',
      source: 'proposed-routes',
      filter: ['==', ['get', 'type'], 'chamber'], // Фильтруем только камеры из GeoJSON
      paint: {
        'circle-radius': 6,
        'circle-color': '#fff',
        'circle-stroke-width': 2,
        'circle-stroke-color': '#ff4d4f',
      },
    })
  }
}

// Следим за изменением данных от бэка (когда алгоритм отработал)
watch(
  () => props.routingData,
  (newData) => {
    updateRoutingLayers(newData)
  },
  { deep: false },
) // Важно: deep: false, передаем объект целиком, не сканируем его внутри

// Следим за выбором конкретного варианта в боковой панели UI
watch(
  () => props.selectedOptionId,
  (newId, oldId) => {
    if (!map.value) return
    // Сбрасываем старое выделение и подсвечиваем выбранный маршрут через Feature State
    if (oldId !== null)
      map.value.setFeatureState({ source: 'proposed-routes', id: oldId }, { selected: false })
    if (newId !== null)
      map.value.setFeatureState({ source: 'proposed-routes', id: newId }, { selected: true })
  },
)

onBeforeUnmount(() => {
  if (map.value) map.value.remove()
})
</script>

<template>
  <div class="map-wrapper">
    <div ref="mapContainer" class="map-container"></div>
  </div>
</template>

<style scoped>
.map-wrapper,
.map-container {
  width: 100%;
  height: 100%;
  position: relative;
}
</style>
