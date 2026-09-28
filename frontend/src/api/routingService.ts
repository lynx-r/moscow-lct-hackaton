import { STAGE2_PROPERTIES_FAST } from '@/constants/stage2-properties'
import { uploadFile } from '@/helpers/uploadFile'
import type { RoutingResponse } from '@/types/RoutingResponse'
import type { Stage2Properties } from '@/types/Stage2Properties'
import type { FeatureCollection } from 'geojson'
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

  traceRouteDebug(file: Blob): Promise<FeatureCollection> {
    return uploadFile('/routing/process-stage2', file)
  },

  async traceRouteMockDebug(
    properties: Stage2Properties = STAGE2_PROPERTIES_FAST,
  ): Promise<FeatureCollection> {
    const result = await apiClient.post('/routing/process-stage2-json', geojson, {
      params: properties,
    })
    return result.data
  },

  async getDebugData(): Promise<string[]> {
    const result = await apiClient.get('/demo/debug-session-filenames.json')
    return result.data
  },
}
