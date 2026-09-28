import { routingService } from '@/api/routingService'
import type { FeatureCollection } from 'geojson'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import geojson from '../mock/dataset-default.json'

export const useTracingStore = defineStore('tracing', () => {
  const input = ref<FeatureCollection>(geojson as FeatureCollection)
  const result = ref<FeatureCollection | null>(null)
  const selectedVariant = ref('1')
  const variants = computed(() =>
    (result.value?.features ?? [])
      .filter((f) => f.properties?.object_type === 'variant_summary')
      .map((f) => ({ id: String(f.properties!.variant_id), rank: Number(f.properties!.rank) }))
      .sort((a, b) => a.rank - b.rank),
  )
  const summaryData = computed(() => {
    const p = result.value?.features.find(
      (f) =>
        f.properties?.object_type === 'variant_summary' &&
        String(f.properties.variant_id) === selectedVariant.value,
    )?.properties
    return p
      ? {
          totalCost: Number(p.calculated_cost),
          constructionCost: Number(p.construction_cost),
          penalty: Number(p.unconnected_penalty),
          networkLength: Number(p.new_network_length),
          failedOksIds: p.unconnected_oks_ids as (string | number)[],
        }
      : null
  })
  // Keep source objects on the map; only the chosen variant is displayed.
  // Summary features have null geometry and belong in the sidebar, not the map.
  const data = computed<FeatureCollection>(() => ({
    type: 'FeatureCollection',
    features: [
      ...input.value.features,
      ...(result.value?.features ?? []).filter(
        (f) => f.geometry && String(f.properties?.variant_id) === selectedVariant.value,
      ),
    ],
  }))
  const calculate = async (source: FeatureCollection, file: Blob) => {
    const response = await routingService.traceRoute(file)
    input.value = source
    result.value = response.featureCollection
    selectedVariant.value = variants.value[0]?.id ?? '1'
  }
  const trace = async (file: Blob) => {
    const source = JSON.parse(await file.text()) as FeatureCollection
    await calculate(source, file)
  }
  const traceMock = async () => {
    await calculate(
      geojson as FeatureCollection,
      new Blob([JSON.stringify(geojson)], { type: 'application/geo+json' }),
    )
  }
  return { data, result, variants, selectedVariant, summaryData, trace, traceMock }
})
