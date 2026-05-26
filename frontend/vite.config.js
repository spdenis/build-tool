import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/api/sessions': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
