import { uploadFile } from '@/helpers/uploadFile'
import type { RoutingResponse } from '@/types/RoutingResponse'
import geojson from '../mock/dataset-default.json'
import apiClient from './client'

export const routingService = {
  traceRoute(file: Blob): Promise<RoutingResponse> {
    return uploadFile('/routing/process', file)
  },

  async traceRouteMock(): Promise<RoutingResponse> {
    const result = await apiClient.post('/routing/process-json', geojson)
    return result.data
  },
}
