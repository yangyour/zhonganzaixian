import {defineConfig} from 'vitest/config'
import vue from '@vitejs/plugin-vue'
/** Explicit GET-only real PG/OIDC component proof, not normal unit tests or real-browser acceptance. */
export default defineConfig({plugins:[vue()],test:{include:['qa/own-worklog-page.http.ts'],environment:'jsdom',fileParallelism:false,testTimeout:60000,hookTimeout:20000}})
