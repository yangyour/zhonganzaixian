import assert from 'node:assert/strict'
import { createHash, randomUUID } from 'node:crypto'

// Current API verification only. No historical suggestion is overwritten and
// no customer value, runtime configuration, monitoring rule or SLA is activated.
export async function runConfiguration(h) {
  const { padmin, reviewer, msp, tadmin, viewerA } = h.clients
  const state = {}
  const marker = `${h.prefix}_CONFIG`
  const capacityInput = {
    inputs: { hosts: '10', items_per_host: '100', interval_seconds: '10', history_days: 2, bytes_per_value: '80', trend_ratio: '0.5', trend_days: 3, headroom_ratio: '0.25' },
    model: { version: `${marker}_EXPLICIT_ONLY`, bytes_per_trend_row: '128', pool_nvps_limit: '60' },
    proxy_buffer: { site_nvps: '125', outage_seconds: '3600', bytes_per_value: '80', margin_factor: '1.4' },
  }
  const expectedCapacity = {
    nvps: '100', daily_bytes: '691200000', history_bytes: '1382400000', trend_bytes: '4608000',
    single_node_bytes: '1733760000', two_node_bytes: '3467520000', peak_nvps: '200', total_items: '1000', pools: '2',
  }
  const registry = async () => (await padmin.call('/configurations')).items
  const requireBaseline = () => { if (!state.registry) h.block('The original configuration registry was not captured') }
  const assertUnverified = revision => {
    assert.equal(revision.effective_value, null)
    assert.equal(revision.confirmation_status, 'UNVERIFIED')
    assert.equal(revision.tenant_id, padmin.session.tenant.id)
    assert.equal(revision.created_by, padmin.session.user.id)
  }
  const assertEstimate = result => {
    assert.equal(result.tenant_id, padmin.session.tenant.id)
    assert.equal(result.estimate_only, true)
    assert.equal(result.scenario_saved, false)
    assert.equal(result.production_capacity_verified, false)
    assert.equal(result.measurement_verification, 'UNVERIFIED')
    assert.equal(result.model_approval_status, 'UNVERIFIED')
    assert.equal(result.approved_pool_nvps_limit, null)
    assert.equal(result.measured_capacity, null)
    assert.equal(result.poc_status, 'NOT_RUN')
    assert.equal(result.proxy_buffer.failover_verified, false)
  }

  await h.case('C01', '配置登记读取、有效性诊断与待确认边界', async () => {
    state.registry = await registry()
    assert.ok(state.registry.length > 0)
    const ids = new Set(state.registry.map(item => item.key))
    assert.equal(ids.size, state.registry.length)
    const definition = state.registry.find(item => item.decision_status === 'pending' && item.value === null)
    if (!definition) h.block('No pending null-valued configuration is available for a read-only diagnostic')
    state.diagnosticKey = definition.key
    const path = `/settings/effective?${new URLSearchParams({ key: definition.key })}`
    const result = await padmin.call(path)
    assert.equal(result.key, definition.key)
    assert.equal(result.context.tenant_id, padmin.session.tenant.id)
    assert.equal(result.status, 'CONFIG_PENDING')
    assert.equal(result.effective_value, null)
    assert.equal(result.publication.availability, 'NOT_IMPLEMENTED')
    assert.equal(result.customer_verification.availability, 'NOT_IMPLEMENTED')
    assert.equal(result.runtime_definition.availability, 'NOT_IMPLEMENTED')
    assert.ok(result.reasons.length > 0)
    await viewerA.call('/configurations', 'GET', undefined, 403)
    await msp.call(path, 'GET', undefined, 403)
    await padmin.call(`${path}&key=${encodeURIComponent(definition.key)}`, 'GET', undefined, 400)
    await padmin.call(`${path}&tenant_id=${padmin.session.tenant.id}`, 'GET', undefined, 400)
    h.artifact('configuration-registry', {
      entry_count: state.registry.length, original_items_sha256: digest(state.registry),
      diagnostic_key: definition.key, effective_status: result.status, runtime_publication_tested: false,
    })
  })

  await h.case('C02', '独立配置修订提交、审批门禁、撤回及原配置保护', async () => {
    requireBaseline()
    if (!reviewer || reviewer.session.user.id === padmin.session.user.id || reviewer.session.tenant.id !== padmin.session.tenant.id) {
      h.block('Two distinct existing platform administrators in the same tenant are required')
    }
    // The synthetic string is unmistakably test input, never a proposed numeric
    // customer threshold. Avoid any identity, credential or security setting.
    const candidates = state.registry.filter(item => item.type === 'string' && item.decision_status === 'pending'
      && item.contract_value_kind !== 'fixed_constraint' && item.value === null && item.registered_value === null
      && item.draft_value === null && !/password|credential|token|secret|identity|oidc|security/i.test(item.key))
      .sort((a, b) => a.key.localeCompare(b.key))
    let selected, originalRevisions
    for (const candidate of candidates.slice(0, 20)) {
      const rows = (await padmin.call(`/configurations/${encodeURIComponent(candidate.key)}/revisions`)).items
      if (rows.some(row => ['DRAFT', 'IN_REVIEW'].includes(row.workflow_status))) continue
      selected = candidate; originalRevisions = rows; break
    }
    if (!selected) h.block('No pending string setting without a current suggestion or active proposal was available; writes skipped')
    const listPath = `/configurations/${encodeURIComponent(selected.key)}/revisions`
    const latest = (await registry()).find(row => row.key === selected.key)
    assert.deepEqual(latest, selected, 'The selected configuration changed before this run could create its own proposal')
    state.selectedKey = selected.key
    state.originalRevisions = originalRevisions
    const body = {
      proposed_value: `${marker}_UNPUBLISHED_TEST_VALUE`,
      reason: `${marker} 独立虚构流程验收，不能作为客户决定或运行值；完成后撤回`,
      evidence_refs: [{ reference: `document-ref:${marker}`, title: '虚构验收引用，未核验，不代表客户确认', sha256: digest(marker) }],
    }
    const key = randomUUID()
    await viewerA.call(listPath, 'POST', body, 403)
    await padmin.call(listPath, 'POST', body, 403, { noCsrf: true })
    try {
      const created = await padmin.call(listPath, 'POST', body, 200, { key })
      state.revision = created
      h.artifact('configuration-owned-revision', { id: created.id, key: selected.key, status: created.workflow_status, version: created.version })
      assertUnverified(created)
      assert.equal(created.workflow_status, 'DRAFT')
      assert.equal(created.version, 1)
      assert.deepEqual(await padmin.call(listPath, 'POST', body, 200, { key }), created)
      await padmin.call(listPath, 'POST', { ...body, reason: `${marker} changed` }, 409, { key })
      const path = `/configuration-revisions/${created.id}`
      await reviewer.call(`${path}/submit`, 'POST', { expected_version: 1 }, 403)
      const submitKey = randomUUID(), submitBody = { expected_version: 1 }
      const submitted = await padmin.call(`${path}/submit`, 'POST', submitBody, 200, { key: submitKey })
      state.revision = submitted
      assertUnverified(submitted)
      assert.equal(submitted.workflow_status, 'IN_REVIEW')
      assert.equal(submitted.version, 2)
      assert.deepEqual(await padmin.call(`${path}/submit`, 'POST', submitBody, 200, { key: submitKey }), submitted)
      await padmin.call(`${path}/review`, 'POST', { expected_version: 2, decision: 'approve', comment: `${marker} self review must be refused` }, 403)
      await reviewer.call(`${path}/review`, 'POST', { expected_version: 1, decision: 'approve', comment: `${marker} stale version must be refused` }, 409)
      assert.deepEqual(await reviewer.call(path), submitted)
    } finally {
      // Successful approval would be an irreversible workflow terminal state.
      // Only our explicitly identified proposal is withdrawn, even on failure.
      if (state.revision) {
        const path = `/configuration-revisions/${state.revision.id}`
        const current = await padmin.call(path)
        assert.equal(current.created_by, padmin.session.user.id)
        assert.equal(current.reason, body.reason)
        assert.equal(current.configuration_key, selected.key)
        if (['DRAFT', 'IN_REVIEW'].includes(current.workflow_status)) {
          const withdrawBody = { expected_version: current.version, reason: `${marker} 验收结束，撤回本轮虚构提案` }, withdrawKey = randomUUID()
          const withdrawn = await padmin.call(`${path}/withdraw`, 'POST', withdrawBody, 200, { key: withdrawKey })
          state.revision = withdrawn
          assert.deepEqual(await padmin.call(`${path}/withdraw`, 'POST', withdrawBody, 200, { key: withdrawKey }), withdrawn)
        }
        assert.equal(state.revision.workflow_status, 'WITHDRAWN')
        assertUnverified(state.revision)
        h.artifact('configuration-owned-revision-cleanup', { id: state.revision.id, status: state.revision.workflow_status, version: state.revision.version, successful_approval_executed: false })
      }
    }
    const after = (await padmin.call(listPath)).items
    assert.deepEqual(after.filter(row => row.id !== state.revision.id), originalRevisions)
    assert.deepEqual((await registry()).find(row => row.key === selected.key), selected)
    assert.deepEqual(state.revision.history.map(row => row.action), ['CREATE', 'SUBMIT', 'WITHDRAW'])
  })

  await h.case('C03', '阈值候选诊断、冲突识别与未知值保护', async () => {
    const path = '/monitoring/threshold-diagnostics'
    const condition = (comparison, threshold, unit = 'SYNTHETIC_UNIT') => ({ comparison, threshold, unit })
    const body = { direction: 'HIGH_IS_BAD', warning: condition('GT', '70'), critical: condition('GT', '85'), recovery: condition('LT', '65'), trigger_hold_seconds: '300', recovery_hold_seconds: '60' }
    const flags = result => {
      assert.equal(result.tenant_id, padmin.session.tenant.id)
      for (const field of ['publishable', 'units_converted', 'monitoring_applied', 'time_series_evaluated', 'rule_saved', 'writes_performed']) assert.equal(result[field], false)
      assert.equal(result.effective_rule, null)
      assert.equal(result.customer_confirmation, 'UNVERIFIED')
    }
    const consistent = await padmin.call(path, 'POST', body)
    flags(consistent); assert.equal(consistent.status, 'CONSISTENT'); assert.deepEqual(consistent.inputs, body)
    const conflict = await padmin.call(path, 'POST', { ...body, direction: 'LOW_IS_BAD', warning: condition('LT', '95'), critical: condition('LT', '99'), recovery: condition('GT', '99.5') })
    flags(conflict); assert.equal(conflict.status, 'CONFLICT'); assert.equal(conflict.inputs.critical.threshold, '99')
    const unknown = await padmin.call(path, 'POST', Object.fromEntries(Object.keys(body).map(key => [key, null])))
    flags(unknown); assert.equal(unknown.status, 'UNRESOLVED'); assert.ok(Object.values(unknown.inputs).every(value => value === null))
    const precision = await padmin.call(path, 'POST', { ...body, warning: condition('GT', '0.99999999999999999'), critical: condition('GT', '1.000'), recovery: condition('LT', '0.5') })
    flags(precision); assert.equal(precision.status, 'CONSISTENT'); assert.equal(precision.inputs.warning.threshold, '0.99999999999999999')
    await padmin.call(path, 'POST', body, 403, { noCsrf: true })
    await msp.call(path, 'POST', body, 403)
    await padmin.call(path, 'POST', { ...body, warning: condition('GT', 70) }, 400)
    h.artifact('configuration-threshold-diagnostic', { consistent: true, conflict_preserved: true, unknown_preserved: true, exact_decimal: true, writes_performed: false })
  })

  await h.case('C04', '独立工作日历草稿、版本历史与显式时间试算', async () => {
    const path = '/working-calendars'
    const body = {
      name: `${marker}_CALENDAR`, coverage_start: '2026-10-14T00:00:00Z', coverage_end: '2026-10-16T00:00:00Z',
      working_windows: [
        { start: '2026-10-14T01:00:00Z', end: '2026-10-14T03:00:00Z' },
        { start: '2026-10-14T00:00:00Z', end: '2026-10-14T02:00:00Z' },
      ], coverage_complete: true, note: `${marker} 虚构覆盖声明，仅验证时间并集，不生效、不替客户定义工作日历`,
    }
    await msp.call(path, 'POST', body, 403)
    await padmin.call(path, 'POST', body, 403, { noCsrf: true })
    const key = randomUUID(), created = await padmin.call(path, 'POST', body, 200, { key })
    state.calendar = created
    h.artifact('configuration-owned-calendar', { id: created.id, version: created.version, effective: created.effective })
    assert.equal(created.effective, false)
    assert.equal(created.customer_confirmation, 'UNVERIFIED')
    assert.deepEqual(created.draft.working_windows, body.working_windows)
    assert.deepEqual(await padmin.call(path, 'POST', body, 200, { key }), created)
    const detail = `${path}/${created.id}`, scenario = { expected_version: created.version, start: body.coverage_start, required_seconds: 10800 }
    const calculated = await padmin.call(`${detail}/preview`, 'POST', scenario)
    assert.equal(calculated.status, 'CALCULATED')
    assert.equal(calculated.scenario_deadline, '2026-10-14T03:00:00Z')
    assert.equal(calculated.effective_deadline, null)
    assert.equal(calculated.consumed.length, 1)
    const unknown = await padmin.call(`${detail}/preview`, 'POST', { ...scenario, required_seconds: null })
    assert.equal(unknown.reason, 'DURATION_MISSING'); assert.equal(unknown.remaining_duration, null)
    const insufficient = await padmin.call(`${detail}/preview`, 'POST', { ...scenario, required_seconds: 10801 })
    assert.equal(insufficient.reason, 'INSUFFICIENT_WORKING_TIME'); assert.equal(insufficient.remaining_duration, 'PT1S')
    const zero = await padmin.call(`${detail}/preview`, 'POST', { ...scenario, required_seconds: 0 })
    assert.equal(zero.scenario_deadline, body.coverage_start); assert.equal(zero.effective_deadline, null)
    assert.equal((await padmin.call(`${detail}/history`)).items.length, 1)
    const updateBody = { ...body, expected_version: created.version, note: `${marker} 第二个不可变修订，仍不生效` }, updateKey = randomUUID()
    const updated = await padmin.call(detail, 'PATCH', updateBody, 200, { key: updateKey })
    state.calendar = updated
    assert.equal(updated.version, 2)
    assert.equal(updated.effective, false)
    assert.deepEqual(await padmin.call(detail, 'PATCH', updateBody, 200, { key: updateKey }), updated)
    await padmin.call(detail, 'PATCH', updateBody, 409)
    await padmin.call(`${detail}/preview`, 'POST', scenario, 409)
    const history = await padmin.call(`${detail}/history`)
    assert.deepEqual(history.items.map(row => row.version), [2, 1])
    assert.deepEqual(history.items[1].after, created.draft)
    await tadmin.call(detail, 'GET', undefined, 403)
    h.artifact('configuration-calendar-result', { id: updated.id, version: updated.version, effective: false, history_count: 2, original_working_window_count: 2, merged_consumed_count: 1, retained_as_synthetic_draft: true })
  })

  await h.case('C05', '容量与缓冲显式估算、精确大数与未知值', async () => {
    const path = '/capacity/estimates'
    const result = await padmin.call(path, 'POST', capacityInput)
    assertEstimate(result)
    assert.deepEqual(result.input, capacityInput)
    assert.deepEqual(result.outputs, expectedCapacity)
    assert.equal(result.proxy_buffer.estimated_bytes, '50400000')
    const unknown = await padmin.call(path, 'POST', { inputs: null, model: null, proxy_buffer: null })
    assertEstimate(unknown); assert.equal(unknown.status, 'UNRESOLVED'); assert.equal(unknown.outputs, null)
    assert.deepEqual(unknown.missing_inputs, ['inputs', 'model'])
    const big = structuredClone(capacityInput)
    Object.assign(big.inputs, { hosts: '9007199254740993', items_per_host: '1', interval_seconds: '1' })
    const exact = await padmin.call(path, 'POST', big)
    assertEstimate(exact); assert.equal(exact.outputs.total_items, '9007199254740993')
    const zero = structuredClone(capacityInput); zero.inputs.hosts = '0'; zero.proxy_buffer.site_nvps = '0'
    const empty = await padmin.call(path, 'POST', zero)
    assert.equal(empty.outputs.pools, '0'); assert.equal(empty.proxy_buffer.estimated_bytes, '0')
    await padmin.call(path, 'POST', capacityInput, 403, { noCsrf: true })
    await viewerA.call(path, 'POST', capacityInput, 403)
    await padmin.call(path, 'POST', { ...capacityInput, inputs: { ...capacityInput.inputs, interval_seconds: '0' } }, 400)
    state.capacityCalculated = true
    h.artifact('configuration-capacity-math', { independent_hand_calculation: true, exact_large_integer: true, unknown_not_zero: true, scenario_saved: false, production_capacity_verified: false, poc_executed: false })
  })

  await h.case('C06', '独立容量方案草稿、原回执与未核验快照', async () => {
    if (!state.capacityCalculated) h.block('The explicit capacity calculation prerequisite did not pass')
    const path = '/capacity/scenarios'
    const body = { expected_version: 0, label: `${marker}_CAPACITY`, note: `${marker} 虚构容量草稿，未测量、未批准、不得用于生产选型`, calculation: capacityInput, benchmark_reference: null }
    const key = randomUUID(), saved = await padmin.call(path, 'POST', body, 200, { key })
    state.capacity = saved
    h.artifact('configuration-owned-capacity', { id: saved.id, version: saved.version, effective: saved.effective })
    assert.equal(saved.lifecycle_status, 'DRAFT')
    assert.equal(saved.effective, false)
    assert.equal(saved.version, 1)
    assert.equal(saved.benchmark_reference, null)
    assert.deepEqual(saved.calculation, capacityInput)
    assert.deepEqual(saved.snapshot.outputs, expectedCapacity)
    assert.deepEqual(await padmin.call(path, 'POST', body, 200, { key }), saved)
    await padmin.call(path, 'POST', { ...body, note: `${marker} changed` }, 409, { key })
    assert.deepEqual(await padmin.call(`${path}/${saved.id}`), saved)
    const page = await padmin.call(`${path}?${new URLSearchParams({ label_query: body.label, page_size: '10' })}`)
    assert.deepEqual(page.items.map(row => row.id), [saved.id])
    assert.equal(page.has_more, false)
    await msp.call(`${path}/${saved.id}`, 'GET', undefined, 403)
    await padmin.call(path, 'POST', body, 403, { noCsrf: true })
    h.artifact('configuration-capacity-draft-result', { id: saved.id, version: saved.version, effective: false, actual_benchmark_attached: false, retained_as_synthetic_draft: true })
  })

  await h.case('C07', '经营样本中位数、人力对照及空样本边界', async () => {
    const path = '/operations/explicit-comparisons'
    const body = { elapsed_millis: ['9', '2', '1', '3'], staffing: { active_tenants: '30', on_call_fte: '1.5', second_line_fte: '1' } }
    const flags = result => {
      assert.equal(result.tenant_id, padmin.session.tenant.id)
      assert.equal(result.source, 'EXPLICIT_INPUT_NOT_PROJECT_METRICS')
      assert.equal(result.comparison_saved, false)
      assert.equal(result.formal_metrics_available, false)
      assert.equal(result.business_data_queried, false)
      assert.equal(result.sample_median.effective_processing_duration, null)
      assert.equal(result.staffing.effective_tenants_per_fte, null)
    }
    const result = await padmin.call(path, 'POST', body)
    flags(result); assert.deepEqual(result.input, body)
    assert.equal(result.sample_median.median_millis, '2.5')
    assert.equal(result.staffing.explicit_total_fte, '2.5')
    assert.equal(result.staffing.tenants_per_fte, '12')
    const unknown = await padmin.call(path, 'POST', { elapsed_millis: null, staffing: null })
    flags(unknown); assert.equal(unknown.sample_median.reason, 'INPUT_UNAVAILABLE'); assert.equal(unknown.staffing.tenants_per_fte, null)
    const empty = await padmin.call(path, 'POST', { ...body, elapsed_millis: [] })
    assert.equal(empty.sample_median.reason, 'EMPTY_SAMPLE'); assert.equal(empty.sample_median.median_millis, null)
    const mixed = await padmin.call(path, 'POST', { ...body, elapsed_millis: ['0', null, '3'] })
    assert.equal(mixed.sample_median.unknown_count, 1); assert.equal(mixed.sample_median.median_millis, null)
    const zero = await padmin.call(path, 'POST', { ...body, staffing: { active_tenants: '30', on_call_fte: '0', second_line_fte: '0' } })
    assert.equal(zero.staffing.reason, 'ZERO_FTE'); assert.equal(zero.staffing.tenants_per_fte, null)
    const exact = await padmin.call(path, 'POST', { elapsed_millis: ['9007199254740993'], staffing: { active_tenants: '9007199254740993', on_call_fte: '1', second_line_fte: '0' } })
    assert.equal(exact.sample_median.median_millis, '9007199254740993'); assert.equal(exact.staffing.tenants_per_fte, '9007199254740993')
    await msp.call(path, 'POST', body, 403)
    await padmin.call(path, 'POST', body, 403, { noCsrf: true })
    await padmin.call(path, 'POST', { ...body, elapsed_millis: [1] }, 400)
    h.artifact('configuration-operational-comparison', { exact_median_and_fte: true, unknown_sample_preserved: true, zero_fte_undefined: true, exact_large_integer: true, formal_metrics_available: false, business_writes: 0 })
  })

  await h.case('C08', '原配置/原草稿完整保留及本轮对象审计核对', async () => {
    requireBaseline()
    const after = await registry()
    assert.deepEqual(after, state.registry, 'This run must not change any original registry value, draft, version or lifecycle')
    const counts = async (id, action, count) => {
      const page = await padmin.call(`/audit/records?${new URLSearchParams({ resource_id: id, action, page_size: '10' })}`)
      assert.equal(page.has_more, false)
      assert.equal(page.items.filter(row => row.result === 'SUCCEEDED').length, count)
    }
    if (state.revision) {
      const current = await padmin.call(`/configuration-revisions/${state.revision.id}`)
      assert.equal(current.workflow_status, 'WITHDRAWN')
      const revisions = (await padmin.call(`/configurations/${encodeURIComponent(state.selectedKey)}/revisions`)).items
      assert.deepEqual(revisions.filter(row => row.id !== current.id), state.originalRevisions)
      await counts(current.id, 'configuration_revision.create', 1)
      await counts(current.id, 'configuration_revision.withdraw', 1)
      await counts(current.id, 'configuration_revision.approve', 0)
      await counts(current.id, 'configuration_revision.reject', 0)
    }
    if (state.calendar) {
      const current = await padmin.call(`/working-calendars/${state.calendar.id}`)
      assert.equal(current.effective, false)
      await counts(current.id, 'working_calendar.draft.create', 1)
      await counts(current.id, 'working_calendar.draft.update', state.calendar.version - 1)
    }
    if (state.capacity) await counts(state.capacity.id, 'capacity.scenario.create', 1)
    h.artifact('configuration-preservation', {
      original_items_sha256: digest(state.registry), final_items_sha256: digest(after), original_registry_and_drafts_unchanged: true,
      own_revision_withdrawn: state.revision?.workflow_status === 'WITHDRAWN',
      calendar_id: state.calendar?.id ?? null, capacity_id: state.capacity?.id ?? null, runtime_configuration_published: false,
    })
  })
}

const digest = value => createHash('sha256').update(JSON.stringify(value)).digest('hex')
