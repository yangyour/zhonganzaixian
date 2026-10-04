// Bundle production preparation-only asset export module. No requests or credentials in this build.
import {mkdir} from 'node:fs/promises'
import {createRequire} from 'node:module'
import {fileURLToPath} from 'node:url'
const root=fileURLToPath(new URL('../',import.meta.url)),require=createRequire(new URL('../apps/web/package.json',import.meta.url))
await mkdir(new URL('../.local/niko-test/',import.meta.url),{recursive:true})
await require('esbuild').build({stdin:{contents:"export * from './apps/web/src/utils/assetExportJobs';export * from './apps/web/src/utils/assetExportWorkspace';export {ApiClient,ApiError} from './apps/web/src/api/client'",resolveDir:root,sourcefile:'asset-export-client-entry.ts',loader:'ts'},bundle:true,platform:'node',format:'esm',outfile:fileURLToPath(new URL('../.local/niko-test/asset-export-client.mjs',import.meta.url))})
console.log('Built preparation-only asset export production client; HTTP proof remains separate')
