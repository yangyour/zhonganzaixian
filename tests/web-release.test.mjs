import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, dirname, basename, resolve } from 'node:path'
import { verifyWebRelease, checkWebRelease } from '../tools/verify-web-release.mjs'

async function fixture(run) {
  const root = await mkdtemp(join(tmpdir(), 'mop-web-release-unit-'))
  try {
    await mkdir(join(root, 'assets')); await mkdir(join(root, '.vite'))
    await writeFile(join(root, 'index.html'), '<script type="module" src="/assets/entry.js"></script><section id="app-startup"></section><noscript>enable JS</noscript>')
    await writeFile(join(root, 'assets/entry.js'), 'import("./main.js")')
    await writeFile(join(root, 'assets/main.js'), 'export const app = "synthetic unit fixture"')
    await writeFile(join(root, 'assets/page.js'), 'export const page = "synthetic unit fixture"')
    const manifest = { 'index.html': { file: 'assets/entry.js', isEntry: true, dynamicImports: ['src/main.ts'] }, 'src/main.ts': { file: 'assets/main.js', dynamicImports: ['src/page.ts'] }, 'src/page.ts': { file: 'assets/page.js' } }
    await writeFile(join(root, '.vite/manifest.json'), JSON.stringify(manifest))
    await run(root, manifest)
  } finally {
    assert.equal(dirname(resolve(root)).toLowerCase(), resolve(tmpdir()).toLowerCase())
    assert.match(basename(root), /^mop-web-release-unit-/)
    await rm(root, { recursive: true, force: true })
  }
}
test('verifies all chunks, creates deterministic file hashes and includes the mandatory app in its size gate', () => fixture(async root => {
  const first = await verifyWebRelease(root), second = await verifyWebRelease(root)
  assert.equal(first.build_id, second.build_id); assert.equal(first.file_count, 5)
  assert.ok(first.bootstrap_gzip_bytes > 50)
  assert.equal(JSON.parse(await readFile(join(root, 'release-manifest.json'))).build_id, first.build_id)
}))
test('fails on a missing lazy chunk instead of shipping a partial application', () => fixture(async root => {
  await rm(join(root, 'assets/page.js'))
  await assert.rejects(verifyWebRelease(root))
}))
test('fails on an unresolved import reference', () => fixture(async (root, manifest) => {
  manifest['src/main.ts'].dynamicImports = ['missing-page']
  await writeFile(join(root, '.vite/manifest.json'), JSON.stringify(manifest))
  await assert.rejects(verifyWebRelease(root), /MISSING_CHUNK_REFERENCE/)
}))
test('rejects path traversal and external HTML resources', () => fixture(async (root, manifest) => {
  manifest['src/page.ts'].file = '../outside.js'
  await writeFile(join(root, '.vite/manifest.json'), JSON.stringify(manifest))
  await assert.rejects(verifyWebRelease(root), /INVALID_ASSET_PATH/)
  manifest['src/page.ts'].file = 'assets/page.js'
  await writeFile(join(root, '.vite/manifest.json'), JSON.stringify(manifest))
  await writeFile(join(root, 'index.html'), '<script src="https://invalid.example/app.js"></script><section id="app-startup"></section><noscript>enable</noscript>')
  await assert.rejects(verifyWebRelease(root), /NONLOCAL_ENTRY_RESOURCE/)
}))
test('rejects private files, debug maps and missing startup recovery', () => fixture(async root => {
  for (const name of ['.env', 'assets/main.js.map']) {
    await writeFile(join(root, name), 'synthetic unit content')
    await assert.rejects(verifyWebRelease(root), /PRIVATE_OR_DEBUG_FILE_IN_RELEASE/)
    await rm(join(root, name))
  }
  await writeFile(join(root, 'index.html'), '<script src="/assets/entry.js"></script>')
  await assert.rejects(verifyWebRelease(root), /MISSING_STARTUP_RECOVERY/)
}))
test('fails the technical size gate instead of weakening its budget', () => fixture(async root => {
  await assert.rejects(verifyWebRelease(root, { bootstrapBudget: 1 }), /BOOTSTRAP_SIZE_BUDGET_EXCEEDED/)
}))
test('accepts Vite generated shared keys for the mandatory dynamic main entry', () => fixture(async (root, manifest) => {
  manifest['_main-hash.js'] = manifest['src/main.ts']; delete manifest['src/main.ts']
  manifest['index.html'].dynamicImports = ['_main-hash.js']
  await writeFile(join(root, '.vite/manifest.json'), JSON.stringify(manifest))
  assert.ok((await verifyWebRelease(root)).bootstrap_gzip_bytes > 50)
}))
test('requires the HTML to reference its declared entry script', () => fixture(async root => {
  await writeFile(join(root, 'index.html'), '<script src="/assets/page.js"></script><section id="app-startup"></section><noscript>enable</noscript>')
  await assert.rejects(verifyWebRelease(root), /ENTRY_NOT_REFERENCED/)
}))
test('checks a shipped release without rewriting its manifest and detects changed bytes', () => fixture(async root => {
  const original = await verifyWebRelease(root)
  assert.equal((await checkWebRelease(root, original.build_id)).build_id, original.build_id)
  await assert.rejects(checkWebRelease(root, '0'.repeat(64)), /UNEXPECTED_RELEASE_BUILD/)
  const before = await readFile(join(root, 'release-manifest.json'), 'utf8')
  await writeFile(join(root, 'assets/page.js'), 'changed deployment bytes')
  await assert.rejects(checkWebRelease(root), /RELEASE_MANIFEST_MISMATCH/)
  assert.equal(await readFile(join(root, 'release-manifest.json'), 'utf8'), before)
}))
test('counts required CSS once and fails an exceeded stylesheet budget', () => fixture(async (root, manifest) => {
  await writeFile(join(root, 'assets/main.css'), 'body{color:#25364c;background:#fff}')
  manifest['index.html'].css = ['assets/main.css']; manifest['src/main.ts'].css = ['assets/main.css']
  await writeFile(join(root, '.vite/manifest.json'), JSON.stringify(manifest))
  const report = await verifyWebRelease(root)
  assert.ok(report.bootstrap_css_gzip_bytes > 0)
  await assert.rejects(verifyWebRelease(root, { cssBudget: 1 }), /CSS_SIZE_BUDGET_EXCEEDED/)
}))
