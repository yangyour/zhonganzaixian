// Bundles the actual production API client and registration read validators. No network or credentials.
import {mkdir} from 'node:fs/promises'
import {createRequire} from 'node:module'
import {fileURLToPath} from 'node:url'
const root=fileURLToPath(new URL('../',import.meta.url)),require=createRequire(new URL('../apps/web/package.json',import.meta.url))
await mkdir(new URL('../.local/niko-test/',import.meta.url),{recursive:true})
await require('esbuild').build({stdin:{contents:"export {ApiClient} from './apps/web/src/api/client'; export * from './apps/web/src/utils/ticketReader'",resolveDir:root,sourcefile:'ticket-reader-client-entry.ts',loader:'ts'},bundle:true,platform:'node',format:'esm',outfile:fileURLToPath(new URL('../.local/niko-test/ticket-reader-client.mjs',import.meta.url))})
console.log('Built actual ticket read validators; HTTP and browser checks not executed')
