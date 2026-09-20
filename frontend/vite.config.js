import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 本机 8080 常被其他项目占用，用 VITE_BACKEND_URL 覆盖后端地址
const backend = process.env.VITE_BACKEND_URL || 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: backend,
        changeOrigin: true,
      },
      '/sse': {
        target: backend,
        changeOrigin: true,
      },
    },
  },
})
