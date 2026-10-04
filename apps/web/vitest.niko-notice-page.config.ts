import {defineConfig} from 'vitest/config'
import vue from '@vitejs/plugin-vue'
/** Explicit real PG/OIDC component integration. Not part of normal unit glob or real-browser acceptance. */
export default defineConfig({plugins:[vue()],test:{include:['qa/change-notice-page.http.ts'],environment:'jsdom',fileParallelism:false,testTimeout:60000,hookTimeout:20000}})
