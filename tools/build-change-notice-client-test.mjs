// Bundle production unreviewed change notice validation/state only. No requests, credentials or device execution.
import {mkdir} from 'node:fs/promises'
import {createRequire} from 'node:module'
import {fileURLToPath} from 'node:url'
const root=fileURLToPath(new URL('../',import.meta.url)),require=createRequire(new URL('../apps/web/package.json',import.meta.url))
await mkdir(new URL('../.local/niko-test/',import.meta.url),{recursive:true})
await require('esbuild').build({stdin:{contents:"export * from './apps/web/src/utils/changeNoticeDrafts';export * from './apps/web/src/utils/changeNoticeWorkspace';export {ApiClient,ApiError} from './apps/web/src/api/client'",resolveDir:root,sourcefile:'change-notice-client-entry.ts',loader:'ts'},bundle:true,platform:'node',format:'esm',outfile:fileURLToPath(new URL('../.local/niko-test/change-notice-client.mjs',import.meta.url))})
console.log('Built production unreviewed change notice client; HTTP/browser proof remains separate')
