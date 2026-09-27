import { routingService } from '@/api/routingService'
import type { FeatureCollection } from 'geojson'
import { defineStore } from 'pinia'
import { ref } from 'vue'
import geojson from '../mock/dataset-default.json'

export const useTracingStore = defineStore('tracing', () => {
  const data = ref<FeatureCollection>(geojson as FeatureCollection)
  const isCalculating = ref(false)

  const trace = async (file: Blob) => {
    const result = await routingService.traceRoute(file)
    console.log(result)
    data.value = result.featureCollection
  }

  const mockTrace = async () => {
    console.log('Используем GeoJson по-умолчанию')
    const result = await routingService.traceRouteMock()
    console.log(result)
    data.value = result.featureCollection
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
    } catch (error) {
      throw error
    } finally {
      isCalculating.value = false
    }
  }

  return { data, isCalculating, startTracing, mockTrace }
})
