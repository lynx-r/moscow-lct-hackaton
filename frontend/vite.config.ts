import { fileURLToPath, URL } from 'node:url'

import tailwindcss from '@tailwindcss/vite'
import vue from '@vitejs/plugin-vue'
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
    Components({
      // Указываем папки, из которых нужно автоматически импортировать компоненты
      dirs: ['src/components/ui', 'src/components'],
      // Генерировать типы для TypeScript (выключите, если используете чистый JS)
      dts: true,
      // Разрешаем импорт расширений .vue
      extensions: ['vue'],
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
