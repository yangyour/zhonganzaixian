// Bundle the actual read validators and HTTP client; no network or credentials here.
import {mkdir} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url)),require=createRequire(new URL('../apps/web/package.json',import.meta.url));
await mkdir(new URL('../.local/niko-test/',import.meta.url),{recursive:true});
await require('esbuild').build({stdin:{contents:"export {ApiClient} from './apps/web/src/api/client'; export {validateReportOperation} from './apps/web/src/utils/reportOperation'; export {validateConfigurationDiagnostic} from './apps/web/src/utils/configurationDiagnostic'; export {readMetadataCurrent} from './apps/web/src/utils/assetMetadataWorkspace'; export * from './apps/web/src/utils/utcInstant'",resolveDir:root,sourcefile:'strict-utc-client-entry.ts',loader:'ts'},bundle:true,platform:'node',format:'esm',outfile:fileURLToPath(new URL('../.local/niko-test/strict-utc-client.mjs',import.meta.url))});
console.log('Built actual UTC read validators; no browser or HTTP test executed');
