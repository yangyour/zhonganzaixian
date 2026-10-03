// Build the actual frontend module for Node HTTP QA. No credentials, test doubles or network requests.
import { mkdir } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
const require=createRequire(new URL('../apps/web/package.json',import.meta.url));
const {build}=require('esbuild');
await mkdir(new URL('../.local/niko-test/',import.meta.url),{recursive:true});
await build({stdin:{contents:"export * from './apps/web/src/utils/ticket'; export * from './apps/web/src/utils/writeFailure'; export {readAcceptedReport} from './apps/web/src/utils/reportOperation'; export {ApiClient} from './apps/web/src/api/client'",resolveDir:root,sourcefile:'report-retry-client-test-entry.ts',loader:'ts'},bundle:true,platform:'node',format:'esm',outfile:fileURLToPath(new URL('../.local/niko-test/report-retry-client.mjs',import.meta.url))});
console.log('Built actual report retry helper module for explicit Node HTTP verification; no browser test ran');
