// Development-only synthetic full-shell preview. Injects qa/synthetic-api.ts ahead of the
// product bootstrap so every /api/v1 request is answered in-page with labelled synthetic data.
// Not a product build, not an integration test, and never forwards requests to any server.
import { fileURLToPath } from 'node:url'
import { defineConfig, mergeConfig, type ConfigEnv, type UserConfig } from 'vite'
import base from '../vite.config'

const repositoryRoot = fileURLToPath(new URL('../../..', import.meta.url))

export default defineConfig((env: ConfigEnv) => mergeConfig(
  (typeof base === 'function' ? base(env) : base) as UserConfig,
  {
    server: { fs: { allow: [repositoryRoot] } },
    plugins: [{
      name: 'mop-synthetic-preview',
      apply: 'serve',
      // A head module script runs before the body bootstrap module (document order).
      transformIndexHtml: () => [{ tag: 'script', attrs: { type: 'module', src: '/qa/synthetic-api.ts' }, injectTo: 'head-prepend' as const }],
    }],
  } satisfies UserConfig,
))
