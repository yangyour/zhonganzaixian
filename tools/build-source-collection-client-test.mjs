// Bundle production collection validation/workspace only; no requests or credential reads.
import {mkdir} from 'node:fs/promises'
import {createRequire} from 'node:module'
import {fileURLToPath} from 'node:url'
const root=fileURLToPath(new URL('../',import.meta.url)),require=createRequire(new URL('../apps/web/package.json',import.meta.url))
await mkdir(new URL('../.local/niko-test/',import.meta.url),{recursive:true})
await require('esbuild').build({stdin:{contents:"export * from './apps/web/src/utils/inspectionSourceCollections';export * from './apps/web/src/utils/inspectionSourceCollectionWorkspace';export {ApiClient} from './apps/web/src/api/client'",resolveDir:root,sourcefile:'source-collection-client-entry.ts',loader:'ts'},bundle:true,platform:'node',format:'esm',outfile:fileURLToPath(new URL('../.local/niko-test/source-collection-client.mjs',import.meta.url))})
console.log('Built production source collection client; actual HTTP/browser verification separate')
