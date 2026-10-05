import {defineConfig} from 'vitest/config'
import vue from '@vitejs/plugin-vue'
/** Explicit native component + actual HTTP proof only; no browser/CSS/mobile claim. */
export default defineConfig({plugins:[vue()],test:{include:['qa/own-preference-page.http.ts'],environment:'jsdom',fileParallelism:false,testTimeout:60000,hookTimeout:20000}})
