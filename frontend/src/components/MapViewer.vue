<!-- MapViewer.vue -->
<script setup lang="ts">
import { useTracingStore } from '@/stores/tracing'
import { useColorMode } from '@vueuse/core'
import type { FeatureCollection } from 'geojson'
import { Map, type LngLatLike } from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { storeToRefs } from 'pinia'
import { computed, onBeforeUnmount, onMounted, shallowRef, useTemplateRef, watch } from 'vue'

const DEFAULT_ZOOM = 14
const DEFAULT_CENTER: LngLatLike = [37.6173, 55.7558] // Координаты центра Москвы [lng, lat]

const mode = useColorMode()

const tracingStore = useTracingStore()
const { data } = storeToRefs(tracingStore)

const props = defineProps({
  // ID выбранного пользователем варианта (Опция 1, Опция 2 и т.д.)
  // selectedOptionId: { type: [String, Number], default: null },
})

const mapContainer = useTemplateRef('mapContainer')
const map = shallowRef<Map | null>(null) // Важно: shallowRef, чтобы не ломать WebGL-контекст реактивностью

const mapStyle = computed(() =>
  mode.value === 'light'
    ? 'https://basemaps.cartocdn.com/gl/positron-gl-style/style.json'
    : 'https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json',
)
const mapStyleWithApiKey = computed(() => `${mapStyle.value}?${import.meta.env.VITE_CARTO_API_KEY}`)

// Инициализация карты
onMounted(() => {
  if (!mapContainer.value) {
    throw 'Не удалось создать контейнер карты'
  }

  map.value = new Map({
    container: mapContainer.value,
    style: mapStyleWithApiKey.value, // style URL
    // style: 'https://basemaps.cartocdn.com/gl/positron-gl-style/style.json', // Темная подложка идеальна для инженерных сетей
    center: DEFAULT_CENTER, // Центрируем на нужный регион
    zoom: DEFAULT_ZOOM,
  })

  // map.value.on('load', () => {
  //   initInfrastructureLayers()
  //   updateRoutingLayers(props.routingData)
  // })
})

// Инициализация базовых слоев (дороги, существующие сети, запретные зоны)
function initInfrastructureLayers() {
  if (!map.value) {
    return
  }

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
function updateRoutingLayers(geoJsonData: FeatureCollection) {
  if (!map.value || !geoJsonData) {
    return
  }

  const source = geoJsonData.features.find((f) => f.properties?.object_type === 'source')
  if (source) {
    if (source.geometry.type === 'Point') {
      map.value.setCenter(source.geometry.coordinates as [number, number])
      map.value.setZoom(DEFAULT_ZOOM)
    }
  }

  // Динамически обновляем или добавляем источник данных предложенных маршрутов
  if (map.value.getSource('proposed-routes')) {
    map.value.getSource('proposed-routes')?.setData(geoJsonData)
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
  data,
  (newData) => {
    updateRoutingLayers(newData)
  },
  { deep: false },
) // Важно: deep: false, передаем объект целиком, не сканируем его внутри

// Следим за выбором конкретного варианта в боковой панели UI
// watch(
//   () => props.selectedOptionId,
//   (newId, oldId) => {
//     if (!map.value) return
//     // Сбрасываем старое выделение и подсвечиваем выбранный маршрут через Feature State
//     if (oldId !== null)
//       map.value.setFeatureState({ source: 'proposed-routes', id: oldId }, { selected: false })
//     if (newId !== null)
//       map.value.setFeatureState({ source: 'proposed-routes', id: newId }, { selected: true })
//   },
// )

onBeforeUnmount(() => {
  if (map.value) {
    map.value.remove()
  }
})
</script>

<template>
  <div ref="mapContainer" class="w-full h-100vh"></div>
</template>
