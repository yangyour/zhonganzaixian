// Bundle production backup proposal validation/state only. No requests, credentials or device execution.
import {mkdir} from 'node:fs/promises'
import {createRequire} from 'node:module'
import {fileURLToPath} from 'node:url'
const root=fileURLToPath(new URL('../',import.meta.url)),require=createRequire(new URL('../apps/web/package.json',import.meta.url))
await mkdir(new URL('../.local/niko-test/',import.meta.url),{recursive:true})
await require('esbuild').build({stdin:{contents:"export * from './apps/web/src/utils/backupPlanDrafts';export * from './apps/web/src/utils/backupPlanWorkspace';export {ApiClient,ApiError} from './apps/web/src/api/client'",resolveDir:root,sourcefile:'backup-plan-client-entry.ts',loader:'ts'},bundle:true,platform:'node',format:'esm',outfile:fileURLToPath(new URL('../.local/niko-test/backup-plan-client.mjs',import.meta.url))})
console.log('Built production backup proposal client; HTTP/browser proof remains separate')
