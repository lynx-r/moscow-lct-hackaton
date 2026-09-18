import { routingService } from '@/api/routingService'
import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useTracingStore = defineStore('tracing', () => {
  const data = ref()

  const trace = async (file: Blob) => {
    const result = await routingService.traceRoute(file)
    console.log(result)
    data.value = result
  }

  return { data, trace }
})
