import { routingService } from '@/api/routingService'
import type { FeatureCollection } from 'geojson'
import { defineStore } from 'pinia'
import { ref } from 'vue'
import geojson from '../mock/dataset-default.json'

export const useTracingStore = defineStore('tracing', () => {
  const data = ref<FeatureCollection>(geojson as FeatureCollection)

  const trace = async (file: Blob) => {
    const result = await routingService.traceRoute(file)
    console.log(result)
    data.value = result.featureCollection
  }

  const traceMock = async () => {
    console.log('Используем GeoJson по-умолчанию')
    const result = await routingService.traceRouteMock()
    console.log(result)
    data.value = result.featureCollection
  }

  return { data, trace, traceMock }
})
