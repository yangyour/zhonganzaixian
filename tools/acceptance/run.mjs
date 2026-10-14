import { createHarness } from './runtime.mjs'

const selected = process.argv.slice(2)
if (!selected.length || selected.some(name => !['identity','resources','services','configuration','intake','readmodels'].includes(name))) {
  console.error('Select explicit feature groups: identity resources services configuration intake readmodels. This creates labelled project test data.')
  process.exitCode = 2
} else {
  let h
  try {
    h = createHarness()
    await h.initialize()
    for (const name of selected) {
      const module = await import('./' + name + '.mjs')
      await module['run' + name[0].toUpperCase() + name.slice(1)](h)
    }
    const summary = h.finish()
    console.log(JSON.stringify({ run_id: h.runId, summary, requests: h.evidence.requests.length, credentials_recorded: false }))
    process.exitCode = summary.FAIL || summary.BLOCKED ? 1 : 0
  } catch (error) {
    h?.finish()
    console.error(JSON.stringify({ status: 'STOPPED', error_type: error.name, details: 'omitted; inspect recorded case and response codes' }))
    process.exitCode = 1
  }
}
