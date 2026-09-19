import type { Feature } from './Feature'

export interface FeatureCollection {
  type: string
  features: Feature[]
}
