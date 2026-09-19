import type { Geometry } from 'geojson'

export interface Feature {
  geometry: Geometry
  properties: Record<string, any>
  type: string
}
