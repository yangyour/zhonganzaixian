import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = process.env.MOP_API_PROXY_TARGET || env.MOP_API_ORIGIN || 'http://127.0.0.1:8080'
  const sameOriginProxy = { target, changeOrigin: false }
  return {
    plugins: [vue()],
    build: { manifest: true, sourcemap: false },
    server: {
      host: '127.0.0.1', port: 5173, strictPort: true,
      proxy: { '/api': sameOriginProxy, '/oauth2': sameOriginProxy, '/login/oauth2': sameOriginProxy, '/auth': sameOriginProxy },
    },
    preview: { host: '127.0.0.1', port: 5173, strictPort: true },
  }
})
