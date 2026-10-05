// Reuse reviewed real-HTTP scenarios in a separate designated-server namespace.
// Historical output/control files are immutable; generated runners live under .local.
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
const root = new URL('../', import.meta.url)
const scenarios = {
  backup: 'niko-backup-plan-draft-http.mjs',
  change: 'niko-change-notice-draft-http.mjs',
  sources: 'niko-inspection-source-collection-http.mjs',
  worklogs: 'niko-own-worklog-browse-http.mjs',
  preferences: 'niko-own-notification-preference-http.mjs',
  exports: 'niko-asset-export-jobs-http.mjs',
}
const name = process.argv[2]
assert.ok(Object.hasOwn(scenarios, name), 'Select one explicit server scenario')
const runtime = JSON.parse(readFileSync(new URL('deploy/private/server-test-runtime.json', root), 'utf8'))
assert.equal(runtime.project, 'mop-platform-test')
assert.equal(new URL(runtime.public_origin).port, '10010')
const contract = JSON.parse(readFileSync(new URL('contracts/local-api.openapi.json', root), 'utf8'))
assert.equal(contract.info.version, '0.44.0')
const original = readFileSync(new URL('tests/' + scenarios[name], root), 'utf8')
assert.ok(original.includes("base='http://127.0.0.1:8080/api/v1'"))
const source = original
  .replace("base='http://127.0.0.1:8080/api/v1'", 'base=' + JSON.stringify(runtime.public_origin + '/api/v1'))
  .replace(/assert\.equal\(contract\.info\.version,'0\.[0-9]+\.0'\)/g, "assert.equal(contract.info.version,'0.44.0')")
  .replace(/api_version\s*:\s*'0\.[0-9]+\.0'/g, "api_version:'0.44.0'")
  .replace(/postgresql\s*:\s*'17\.11'/g, "postgresql:'17.9'")
  .replaceAll('.local/niko-test/', '.local/server-acceptance/')
  .replaceAll('niko-local-test', 'designated-server-acceptance')
  .replace(/docs\/evidence\/niko-([A-Za-z0-9-]+)-2026100[45]/g, 'docs/evidence/server-$1-20261005')
assert.notEqual(source, original)
assert.equal(source.includes("base='http://127.0.0.1:8080/api/v1'"), false)
assert.equal(source.includes('.local/niko-test/'), false)
assert.equal(source.includes('docs/evidence/niko-'), false)
mkdirSync(new URL('.local/server-acceptance/', root), { recursive: true })
const generated = new URL('.local/server-acceptance-' + name + '.mjs', root)
writeFileSync(generated, source)
writeFileSync(new URL('.local/server-acceptance/' + name + '-runner-provenance.json', root), JSON.stringify({
  original_file: 'tests/' + scenarios[name], original_sha256: createHash('sha256').update(original).digest('hex'),
  generated_sha256: createHash('sha256').update(source).digest('hex'), environment: 'designated-server-acceptance',
  transformations: ['explicit server origin', 'actual 0.44 contract version', 'separate private controls and sessions', 'separate dated evidence'],
}, null, 2) + '\n')
const result = spawnSync(process.execPath, [generated.pathname.replace(/^\/([A-Za-z]:)/, '$1')], { cwd: new URL('.', root), stdio: 'inherit', timeout: 240000 })
process.exitCode = result.status ?? 1
