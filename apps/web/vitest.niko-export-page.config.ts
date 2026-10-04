import {defineConfig} from 'vitest/config'
import vue from '@vitejs/plugin-vue'
/** Explicit real-component run only; no browser/CSS/phone claim. */
export default defineConfig({plugins:[vue()],test:{include:['qa/asset-export-page.http.ts'],environment:'jsdom',fileParallelism:false,testTimeout:60000,hookTimeout:20000}})
