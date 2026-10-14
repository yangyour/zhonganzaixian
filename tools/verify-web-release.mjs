import { readFile, writeFile, readdir, realpath, stat } from 'node:fs/promises'
import { resolve, relative, sep } from 'node:path'
import { pathToFileURL } from 'node:url'
import { createHash } from 'node:crypto'
import { gzipSync } from 'node:zlib'

const hash = value => createHash('sha256').update(value).digest('hex')
export async function verifyWebRelease(directory, { writeManifest = true, bootstrapBudget = 256 * 1024 } = {}) {
  const root = await realpath(resolve(directory))
  const file = async name => {
    if (typeof name !== 'string' || !name || name.startsWith('/') || /[\\?#\u0000-\u001f]/.test(name) || name.split('/').some(part => !part || part === '.' || part === '..')) throw Error('INVALID_ASSET_PATH')
    const target = await realpath(resolve(root, name))
    if (!target.startsWith(root + sep) || !(await stat(target)).isFile()) throw Error('ASSET_OUTSIDE_RELEASE')
    return readFile(target)
  }
  const manifest = JSON.parse((await file('.vite/manifest.json')).toString('utf8'))
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) throw Error('INVALID_VITE_MANIFEST')
  const entries = Object.entries(manifest).filter(([, item]) => item.isEntry)
  const list = value => { if (value === undefined) return []; if (!Array.isArray(value) || value.some(v => typeof v !== 'string')) throw Error('INVALID_ASSET_LIST'); return value }
  const applicationEntries = [...new Set(entries.flatMap(([, item]) => list(item.dynamicImports)))]
  if (!entries.length || !applicationEntries.length) throw Error('MISSING_APPLICATION_ENTRY')
  for (const item of Object.values(manifest)) {
    await file(item.file)
    for (const dependency of [...list(item.imports), ...list(item.dynamicImports)]) if (!Object.hasOwn(manifest, dependency)) throw Error('MISSING_CHUNK_REFERENCE')
    for (const asset of [...list(item.css), ...list(item.assets)]) await file(asset)
  }
  const html = (await file('index.html')).toString('utf8')
  if (!html.includes('id="app-startup"') || !html.includes('<noscript>')) throw Error('MISSING_STARTUP_RECOVERY')
  const references = [...html.matchAll(/<(?:script|link)\b[^>]*\b(?:src|href)=["']([^"']+)["'][^>]*>/gi)].map(match => match[1])
  if (!references.some(path => path.endsWith('.js'))) throw Error('MISSING_ENTRY_SCRIPT')
  for (const reference of references) {
    if (!reference.startsWith('/') || reference.startsWith('//')) throw Error('NONLOCAL_ENTRY_RESOURCE')
    await file(reference.slice(1))
  }
  if (entries.some(([, item]) => !references.includes('/' + item.file))) throw Error('ENTRY_NOT_REFERENCED')
  const closure = new Set()
  function visit(key) { if (closure.has(key)) return; closure.add(key); for (const dependency of list(manifest[key].imports)) visit(dependency) }
  for (const [key] of entries) visit(key)
  // Vite may name the main entry by source path OR a generated shared-chunk key.
  for (const key of applicationEntries) visit(key)
  let bootstrapGzip = 0
  const initialFiles = new Set([...closure].map(key => manifest[key].file))
  for (const name of initialFiles) bootstrapGzip += gzipSync(await file(name)).byteLength
  if (bootstrapGzip > bootstrapBudget) throw Error('BOOTSTRAP_SIZE_BUDGET_EXCEEDED')
  const records = []
  async function walk(folder) {
    for (const entry of await readdir(folder, { withFileTypes: true })) {
      if (entry.isSymbolicLink()) throw Error('SYMLINK_IN_RELEASE')
      const path = resolve(folder, entry.name), name = relative(root, path).split(sep).join('/')
      if (entry.name === '.env' || entry.name.startsWith('.env.') || entry.name === 'private' || entry.name.endsWith('.map')) throw Error('PRIVATE_OR_DEBUG_FILE_IN_RELEASE')
      if (entry.isDirectory()) await walk(path)
      else if (entry.isFile() && name !== 'release-manifest.json') { const bytes = await file(name); records.push({ path: name, bytes: bytes.byteLength, sha256: hash(bytes) }) }
    }
  }
  await walk(root); records.sort((a, b) => a.path.localeCompare(b.path, 'en'))
  const report = { schema: 1, scope: 'static-artifacts-only', build_id: hash(JSON.stringify(records)), bootstrap_gzip_bytes: bootstrapGzip, bootstrap_budget_bytes: bootstrapBudget, file_count: records.length, files: records }
  if (writeManifest) await writeFile(resolve(root, 'release-manifest.json'), JSON.stringify(report, null, 2) + '\n')
  return report
}
export async function checkWebRelease(directory, expectedBuildId) {
  const actual = await verifyWebRelease(directory, { writeManifest: false })
  const recorded = JSON.parse(await readFile(resolve(directory, 'release-manifest.json'), 'utf8'))
  if (recorded.build_id !== actual.build_id || JSON.stringify(recorded.files) !== JSON.stringify(actual.files) || recorded.file_count !== actual.file_count) throw Error('RELEASE_MANIFEST_MISMATCH')
  if (expectedBuildId && actual.build_id !== expectedBuildId) throw Error('UNEXPECTED_RELEASE_BUILD')
  return actual
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const position = process.argv.indexOf('--dist')
    const directory = position < 0 ? 'apps/web/dist' : process.argv[position + 1]
    const expectedPosition = process.argv.indexOf('--expected-build-id')
    const expected = expectedPosition < 0 ? undefined : process.argv[expectedPosition + 1]
    if (expectedPosition >= 0 && !/^[a-f0-9]{64}$/.test(expected ?? '')) throw Error('INVALID_EXPECTED_BUILD')
    if (expected && !process.argv.includes('--check')) throw Error('EXPECTED_BUILD_REQUIRES_CHECK')
    const report = process.argv.includes('--check') ? await checkWebRelease(directory, expected) : await verifyWebRelease(directory)
    console.log(JSON.stringify({ status: 'PASSED', files: report.file_count, bootstrap_gzip_bytes: report.bootstrap_gzip_bytes, build_id: report.build_id, scope: report.scope }))
  } catch (error) {
    console.error(JSON.stringify({ status: 'FAILED', reason: /^[A-Z_]+$/.test(error.message) ? error.message : 'RELEASE_RESOURCE_INVALID_OR_MISSING' }))
    process.exitCode = 1
  }
}
