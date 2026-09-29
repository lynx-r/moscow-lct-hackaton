import { routingService } from '@/api/routingService'
import type { FeatureCollection } from 'geojson'
import { defineStore } from 'pinia'
import { ref } from 'vue'
import geojson from '../mock/dataset-default.json'

export const useTracingDebugStore = defineStore('tracing-debug', () => {
  const defaultGeojson = geojson as FeatureCollection

  const data = ref<FeatureCollection>(defaultGeojson)
  const debugDataFileUrls = ref<string[]>([])
  const isCalculating = ref(false)

  const trace = async (file: Blob) => {
    data.value = await routingService.traceRouteDebug(file)
  }

  const mockTrace = async () => {
    console.log('Используем GeoJson по-умолчанию')
    const res = await routingService.traceRouteMockDebug()
    data.value = {
      ...geojson,
      features: [...geojson.features, ...res.features],
    } as FeatureCollection
  }

  // Функция обработки клика по кнопке расчета
  const startTracing = async (file: File | undefined) => {
    isCalculating.value = true

    try {
      if (!file) {
        await mockTrace()
      } else {
        await trace(file)
      }

      debugDataFileUrls.value = await routingService.getDebugData()
    } finally {
      isCalculating.value = false
    }
  }

  return {
    data,
    isCalculating,
    debugDataFileUrls,
    startTracing,
    mockTrace,
  }
})
