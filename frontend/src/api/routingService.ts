import { uploadFile } from '@/helpers/uploadFile'

export const routingService = {
  traceRoute(file: Blob) {
    return uploadFile('/routing/process', file)
  },
}
