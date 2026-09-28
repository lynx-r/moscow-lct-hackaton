import { fileURLToPath, URL } from 'node:url'

import tailwindcss from '@tailwindcss/vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { defineConfig } from 'vite'
import vueDevTools from 'vite-plugin-vue-devtools'
import VueRouter from 'vue-router/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    VueRouter({}),
    vue(),
    vueDevTools(),
    tailwindcss(),
    AutoImport({
      imports: ['vue', 'vue-router', 'pinia', '@vueuse/core'],
      // Что импортировать автоматически
      dirs: ['src/composables', 'src/stores'],
    }),
    Components({
      // Указываем папки, из которых нужно автоматически импортировать компоненты
      dirs: ['src/components/ui', 'src/components'],
      // Генерировать типы для TypeScript (выключите, если используете чистый JS)
      dts: true,
      directoryAsNamespace: true,
      collapseSamePrefixes: true,
      // Разрешаем импорт расширений .vue
      extensions: ['vue'],
      deep: true, // Добавить для рекурсивного поиска
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  optimizeDeps: {
    exclude: ['maplibre-gl'],
  },
})
