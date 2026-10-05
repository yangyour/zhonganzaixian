// Bundle the production owner preference protocol and request state. No requests or credentials in the build.
import {mkdir} from 'node:fs/promises'
import {createRequire} from 'node:module'
import {fileURLToPath} from 'node:url'
const root=fileURLToPath(new URL('../',import.meta.url)),require=createRequire(new URL('../apps/web/package.json',import.meta.url))
await mkdir(new URL('../.local/niko-test/',import.meta.url),{recursive:true})
await require('esbuild').build({stdin:{contents:"export * from './apps/web/src/utils/ownNotificationPreferences';export * from './apps/web/src/utils/ownPreferenceWorkspace';export {ApiClient,ApiError} from './apps/web/src/api/client'",resolveDir:root,sourcefile:'own-preference-client-entry.ts',loader:'ts'},bundle:true,platform:'node',format:'esm',outfile:fileURLToPath(new URL('../.local/niko-test/own-preference-client.mjs',import.meta.url))})
console.log('Built own preference production modules; actual HTTP and browser proof remain separate')
