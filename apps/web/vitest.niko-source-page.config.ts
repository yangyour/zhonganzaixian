import {defineConfig} from 'vitest/config'
import vue from '@vitejs/plugin-vue'
/** Explicit existing-PG/OIDC component integration only; never included in the normal unit-test glob. */
export default defineConfig({plugins:[vue()],test:{include:['qa/inspection-source-collection-page.http.ts'],environment:'jsdom',fileParallelism:false,testTimeout:60000,hookTimeout:20000}})
