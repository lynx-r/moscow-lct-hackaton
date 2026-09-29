<!-- MapViewer.vue -->
<script setup lang="ts">
import { useColorMode } from '@vueuse/core'
import type { FeatureCollection } from 'geojson'
import { GeoJSONSource, Map, setWorkerUrl } from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { computed, onBeforeUnmount, onMounted, shallowRef, useTemplateRef, watch } from 'vue'

// ВАЖНО: Используем именно ?worker&url, а не просто ?url
import { DATA_SOURCE_NAME, DEFAULT_CENTER, DEFAULT_ZOOM } from '@/constants/map'
import { OBJECT_TYPES } from '@/constants/object-types'
import maplibreWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'

// Устанавливаем воркер ДО создания объекта карты
setWorkerUrl(maplibreWorkerUrl)

const mode = useColorMode()

const { data, debugDataFileUrls } = defineProps<{
  data: FeatureCollection
  debugDataFileUrls: string[]
}>()

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

  map.value.on('load', () => {
    updateRoutingLayers(data)
  })
})

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
  if (map.value.getSource(DATA_SOURCE_NAME)) {
    console.log('????', geoJsonData)
    const source = map.value.getSource(DATA_SOURCE_NAME)
    if (source instanceof GeoJSONSource) {
      source.setData(geoJsonData)
    }
  } else {
    map.value.addSource(DATA_SOURCE_NAME, { type: 'geojson', data: geoJsonData })

    // Слой пространственных ограничений. Полигоны (Polygon, MultiPolygon)
    map.value.addLayer({
      id: `layer-fill-${OBJECT_TYPES.restriction}`,
      type: 'fill',
      source: DATA_SOURCE_NAME,
      filter: [
        'all',
        ['==', ['get', 'object_type'], OBJECT_TYPES.restriction],
        ['==', ['geometry-type'], 'Polygon'],
      ],
      paint: {
        'fill-color': 'darkred',
        'fill-opacity': 0.5,
        'fill-outline-color': 'red',
      },
    })

    // Слой пространственных ограничений. Линий (LineString, MultiLineString)
    map.value.addLayer({
      id: `layer-line-${OBJECT_TYPES.restriction}`,
      type: 'line',
      source: DATA_SOURCE_NAME,
      filter: [
        'all',
        ['==', ['get', 'object_type'], OBJECT_TYPES.restriction],
        ['==', ['geometry-type'], 'LineString'],
      ],
      layout: {
        'line-join': 'round',
        'line-cap': 'round',
      },
      paint: {
        'line-color': 'darkred',
        'line-width': 2,
      },
    })

    // Слой источников теплоснабжения
    map.value.addLayer({
      id: `layer-circle-${OBJECT_TYPES.source}`,
      type: 'circle',
      source: DATA_SOURCE_NAME,
      filter: ['==', ['get', 'object_type'], OBJECT_TYPES.source],
      paint: {
        'circle-radius': 6,
        'circle-color': 'red',
        'circle-stroke-width': 2,
        'circle-stroke-color': 'darkred',
      },
    })

    // Слой существующей тепловой сети
    map.value.addLayer({
      id: `layer-line-${OBJECT_TYPES.heat_network}`,
      type: 'line',
      source: DATA_SOURCE_NAME,
      filter: ['==', ['get', 'object_type'], OBJECT_TYPES.heat_network],
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

    // Слой существующей тепловой сети
    map.value.addLayer({
      id: `layer-line-${OBJECT_TYPES.graph_edge}`,
      type: 'line',
      source: DATA_SOURCE_NAME,
      filter: ['==', ['get', 'object_type'], OBJECT_TYPES.graph_edge],
      // paint: {
      //   'line-color': [
      //     'case',
      //     ['boolean', ['feature-state', 'selected'], false],
      //     '#ff4d4f',
      //     '#ffa940',
      //   ],
      //   'line-width': ['case', ['boolean', ['feature-state', 'selected'], false], 5, 3],
      // },
    })

    // Слой существующих тепловых камер
    map.value.addLayer({
      id: `layer-circle-${OBJECT_TYPES.heat_chamber}`,
      type: 'circle',
      source: DATA_SOURCE_NAME,
      filter: ['==', ['get', 'object_type'], OBJECT_TYPES.heat_chamber],
      paint: {
        'circle-radius': 6,
        'circle-color': 'orange',
        'circle-stroke-width': 2,
        'circle-stroke-color': 'yellow',
      },
    })

    // Слой существующих точек подключения
    map.value.addLayer({
      id: `layer-circle-${OBJECT_TYPES.oks_connection_point}`,
      type: 'circle',
      source: DATA_SOURCE_NAME,
      filter: ['==', ['get', 'object_type'], OBJECT_TYPES.oks_connection_point],
      paint: {
        'circle-radius': 6,
        'circle-color': 'green',
        'circle-stroke-width': 2,
        'circle-stroke-color': 'lightgreen',
      },
    })
  }
}

// Следим за изменением данных от бэка (когда алгоритм отработал)
watch(
  () => data,
  (newData) => {
    console.log('new data', newData)
    if (!newData) {
      return
    }
    updateRoutingLayers(newData)
  },
  { deep: false },
) // Важно: deep: false, передаем объект целиком, не сканируем его внутри

watch(
  () => debugDataFileUrls,
  (debugUrls) => {
    console.log('???', debugUrls)
  },
)

// Следим за выбором конкретного варианта в боковой панели UI
// watch(
//   () => props.selectedOptionId,
//   (newId, oldId) => {
//     if (!map.value) return
//     // Сбрасываем старое выделение и подсвечиваем выбранный маршрут через Feature State
//     if (oldId !== null)
//       map.value.setFeatureState({ source: DATA_SOURCE_NAME, id: oldId }, { selected: false })
//     if (newId !== null)
//       map.value.setFeatureState({ source: DATA_SOURCE_NAME, id: newId }, { selected: true })
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
  <div id="state-legend" class="legend bg-accent">
    <h4>Типы объектов</h4>
    <div>
      <span class="rounded-none! h-1! mb-1" style="background-color: #ffa940"></span>
      Существующие тепловые сети
    </div>
    <div>
      <span style="background-color: red; border-color: darkred"></span>
      Источник теплоснабжения
    </div>
    <div>
      <span style="background-color: orange; border-color: yellow"></span>
      Существующие тепловые камеры
    </div>
    <div>
      <span style="background-color: green; border-color: lightgreen"></span>
      Существующие точки подключения
    </div>
    <div>
      <span class="rounded-none!" style="background-color: darkred"></span>
      Пространственные ограничения
    </div>
  </div>
</template>

<style scoped>
.legend {
  border-radius: 3px;
  bottom: 30px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
  padding: 10px;
  position: absolute;
  right: 10px;
  z-index: 1; /* Чтобы блок был поверх Canvas карты */
  opacity: 0.8;
}

.legend h4 {
  margin: 0 0 10px;
}

.legend div span {
  border-radius: 50%;
  display: inline-block;
  height: 10px;
  margin-right: 2px;
  width: 10px;
}
</style>
