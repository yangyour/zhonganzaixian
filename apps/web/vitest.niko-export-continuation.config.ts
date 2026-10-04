import {defineConfig} from 'vitest/config'
import vue from '@vitejs/plugin-vue'
export default defineConfig({plugins:[vue()],test:{include:['qa/asset-export-continuation.http.ts'],environment:'jsdom',fileParallelism:false,testTimeout:60000,hookTimeout:20000}})
