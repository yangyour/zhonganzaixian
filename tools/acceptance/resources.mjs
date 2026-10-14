import assert from 'node:assert/strict'
import { createHash, randomUUID } from 'node:crypto'
import { inflateRawSync } from 'node:zlib'

// Real designated-server scenarios. Only this run's synthetic assets, edges,
// export and inbox message are written. Existing jobs are never published.
export async function runResources(h) {
  const { msp, padmin, viewerA, viewerB, tadmin } = h.clients
  const prefix = `${h.prefix}_RESOURCE`
  const state = { assets: [], metadataWrites: 0, warrantyWrites: 0 }
  const requireAssets = () => {
    if (state.assets.length !== 2) h.block('This run has not created both synthetic resource assets')
  }
  const assetPath = () => `/assets/${state.assets[0].id}`
  const metadataPath = () => `${assetPath()}/manual-metadata`
  const requireExport = () => {
    if (!state.exportReceipt) h.block('This run has no confirmed export acceptance receipt')
  }
  const exportPath = () => `/asset-export-jobs/${state.exportReceipt.job_id}`
  const requirePublished = () => {
    requireExport()
    if (!state.published) h.block('This run has no confirmed publication')
  }
  const same = (actual, expected, label) => assert.deepEqual(actual, expected, label)
  const poll = async (fn, options, blockedReason) => {
    try { return await h.waitFor(fn, options) }
    catch (error) {
      if (error?.message === 'POLL_TIMEOUT') h.block(blockedReason)
      throw error
    }
  }
  const assertDraft = asset => {
    assert.equal(asset.tenant_id, msp.session.tenant.id)
    assert.equal(asset.source, 'manual')
    assert.equal(asset.status, 'unknown')
    assert.equal(asset.availability, 'UNKNOWN')
    assert.equal(asset.sync_state, 'PENDING_ONBOARDING')
    assert.equal(asset.sampled_at, null)
  }

  await h.case('R01', '资产草稿创建、幂等、权限和CSRF', async () => {
    assert.ok(prefix.length + 2 <= 100, 'Synthetic asset name must fit the public contract')
    assert.equal(msp.session.tenant.id, viewerA.session.tenant.id)
    assert.notEqual(msp.session.tenant.id, viewerB.session.tenant.id)
    const existing = await msp.call('/assets?page_size=10')
    const site = existing.items.find(asset => asset.tenant_id === msp.session.tenant.id && asset.site_id)?.site_id
    if (!site) h.block('No existing authorized site reference is available; no site or tenant is invented')
    const ip = randomUUID().replaceAll('-', '').slice(0, 8)
    const body = {
      name: `${prefix}_A`, type: 'router',
      management_ip: `2001:db8:${ip.slice(0, 4)}:${ip.slice(4)}::a`, site_id: site,
    }
    await viewerA.call('/assets', 'POST', body, 403)
    await msp.call('/assets', 'POST', body, 403, { noCsrf: true })
    const key = randomUUID()
    const a = await msp.call('/assets', 'POST', body, 200, { key })
    state.assets.push(a)
    h.assetId = a.id
    h.artifact('resource-asset-a', { id: a.id, source: a.source, status: a.status })
    same(await msp.call('/assets', 'POST', body, 200, { key }), a, 'Create replay must return the original asset')
    await msp.call('/assets', 'POST', { ...body, name: `${prefix}_changed` }, 409, { key })
    const b = await msp.call('/assets', 'POST', { ...body, name: `${prefix}_B`, management_ip: body.management_ip.replace(/a$/, 'b') })
    state.assets.push(b)
    h.artifact('resource-asset-b', { id: b.id, source: b.source, status: b.status })
    for (const asset of state.assets) { assertDraft(asset); assert.equal(asset.version, 1) }
  })

  await h.case('R02', '资产列表、详情、字面筛选与跨租户隔离', async () => {
    requireAssets()
    const query = new URLSearchParams({ q: prefix, page_size: '10' })
    const page = await viewerA.call(`/assets?${query}`)
    same(page.items.map(asset => asset.id).sort(), state.assets.map(asset => asset.id).sort())
    assert.equal(page.has_more, false)
    assert.equal(page.next_cursor, null)
    for (const asset of state.assets) {
      same(await viewerA.call(`/assets/${asset.id}`), asset)
      await viewerB.call(`/assets/${asset.id}`, 'GET', undefined, 404)
    }
    const foreign = await viewerB.call(`/assets?${query}`)
    assert.equal(foreign.items.length, 0)
    const literal = await msp.call(`/assets?${new URLSearchParams({ q: `${prefix}%_` })}`)
    assert.equal(literal.items.length, 0, 'Percent and underscore must not become SQL wildcards')
    await msp.call('/assets?page_size=10&page_size=15', 'GET', undefined, 400)
    await msp.call(`/assets?tenant_id=${viewerB.session.tenant.id}`, 'GET', undefined, 400)
    h.artifact('resource-ledger', { synthetic_asset_count: 2, current_tenant_only: true, actual_has_more_branch: false })
  })

  await h.case('R03', '设备资料维护、不可变历史与乐观锁', async () => {
    requireAssets()
    const a = state.assets[0]
    const initial = await msp.call(metadataPath())
    assert.equal(initial.metadata, null)
    assert.equal(initial.can_edit, true)
    const facts = {
      name: a.name, type: 'switch', site_id: a.site_id, brand: 'SYNTHETIC',
      model: `=${h.prefix}`, serial_number: null, location: '虚构验收机柜',
      owner_id: null, business_description: null, purchase_date: null,
      warranty_expiry: null, overdue_service_date: null, collection_method_note: null,
      note: `${h.prefix} 仅为草稿验收，不接入监控`,
    }
    const firstBody = { expected_version: a.version, ...facts }, key = randomUUID()
    const first = await msp.call(metadataPath(), 'PATCH', firstBody, 200, { key })
    state.metadataWrites++
    state.metadata = first
    same(await msp.call(metadataPath(), 'PATCH', firstBody, 200, { key }), first)
    await msp.call(metadataPath(), 'PATCH', { ...firstBody, note: `${h.prefix} changed` }, 409, { key })
    await msp.call(metadataPath(), 'PATCH', firstBody, 409)
    await msp.call(metadataPath(), 'PATCH', { ...firstBody, expected_version: first.asset.version, management_ip: a.management_ip }, 400)
    await viewerA.call(metadataPath(), 'GET', undefined, 403)
    await tadmin.call(metadataPath(), 'PATCH', firstBody, 403)
    const second = await msp.call(metadataPath(), 'PATCH', {
      ...facts, expected_version: first.asset.version, note: `${h.prefix} 第二次虚构资料修订`,
    })
    state.metadataWrites++
    state.metadata = second
    assert.equal(second.asset.version, a.version + 2)
    assert.equal(second.asset.management_ip, a.management_ip)
    assert.equal(second.monitoring_applied, false)
    assert.equal(second.cmdb_applied, false)
    assertDraft(second.asset)
    const history = await msp.call(`${metadataPath()}/history`)
    same(history.items.map(row => row.asset_version), [second.asset.version, first.asset.version])
    same(await msp.call(metadataPath(), 'PATCH', firstBody, 200, { key }), first, 'Old replay must not replace the latest revision')
    assert.equal((await msp.call(metadataPath())).asset.version, second.asset.version)
    const filtered = await msp.call(`/assets?${new URLSearchParams({ q: prefix, type: 'switch', status: 'unknown', site_id: a.site_id })}`)
    same(filtered.items.map(row => row.id), [a.id])
    h.artifact('resource-metadata', { id: a.id, current_version: second.asset.version, history_count: history.items.length, monitoring_applied: false })
  })

  await h.case('R04', '维保声明、日期判断与未核验边界', async () => {
    requireAssets()
    if (!state.metadata) h.block('Synthetic metadata prerequisite was not saved')
    const before = await msp.call(`${metadataPath()}/date-assessment`)
    const date = delta => new Date(Date.parse(`${before.as_of_date}T00:00:00Z`) + delta * 86400000).toISOString().slice(0, 10)
    const current = await msp.call(metadataPath())
    const body = {
      expected_version: current.asset.version, warranty_start: date(-30), warranty_expiry: date(29),
      warranty_supplier: `${h.prefix} 虚构供应商`, overdue_service_date: date(91),
      note: `${h.prefix} 自述维保资料，未核验覆盖和供应商`,
    }
    const key = randomUUID(), path = `${metadataPath()}/warranty`
    const saved = await msp.call(path, 'PATCH', body, 200, { key })
    state.warrantyWrites++
    state.warranty = saved
    same(await msp.call(path, 'PATCH', body, 200, { key }), saved)
    await msp.call(path, 'PATCH', body, 409)
    await msp.call(path, 'PATCH', { ...body, expected_version: saved.asset.version, warranty_start: date(30) }, 400)
    assert.equal(saved.warranty_details.warranty_start, body.warranty_start)
    assert.equal(saved.warranty_details.warranty_supplier, body.warranty_supplier)
    assert.equal(saved.warranty_coverage_verified, false)
    assert.equal(saved.warranty_supplier_verification, 'UNVERIFIED')
    assert.equal(saved.metadata.brand, current.metadata.brand)
    assert.equal(saved.metadata.model, current.metadata.model)
    const assessment = await msp.call(`${metadataPath()}/date-assessment`)
    assert.equal(assessment.coverage_verified, false)
    assert.equal(assessment.warranty_expiry.recorded_date, body.warranty_expiry)
    const days = (Date.parse(body.warranty_expiry) - Date.parse(assessment.as_of_date)) / 86400000
    assert.equal(assessment.warranty_expiry.days_until, days)
    assert.equal(assessment.warranty_expiry.within_30_days, days >= 0 && days <= 30)
    assert.equal(assessment.hazard_lookup_status, 'NOT_IMPLEMENTED')
    assert.equal(assessment.notification_lookup_status, 'NOT_IMPLEMENTED')
    const history = await msp.call(`${path}/history`)
    assert.equal(history.items[0].asset_version, saved.asset.version)
    h.artifact('resource-warranty', { id: saved.asset.id, current_version: saved.asset.version, coverage_verified: false, notification_sent: false })
  })

  await h.case('R05', '设备依赖草稿、幂等、防环与越权拒绝', async () => {
    requireAssets()
    const [a, b] = state.assets, path = `${assetPath()}/dependencies`
    const before = await msp.call(path)
    const edge = (parent, child) => ({ parent_id: parent, child_id: child, proposed_type: null, proposed_suppression_scope: null, proposed_enabled: null })
    const body = { expected_version: before.version, additions: [edge(a.id, b.id)] }, key = randomUUID()
    await viewerA.call(path, 'POST', body, 403)
    await msp.call(path, 'POST', body, 403, { noCsrf: true })
    const saved = await msp.call(path, 'POST', body, 200, { key })
    state.dependencies = saved
    same(await msp.call(path, 'POST', body, 200, { key }), saved)
    assert.equal(saved.version, before.version + 1)
    assert.equal(saved.proposed_edges.length, before.proposed_edges.length + 1)
    assert.equal(saved.monitoring_applied, false)
    assert.equal(saved.lifecycle_status, 'DRAFT')
    assert.equal(saved.effective_rules, null)
    await msp.call(`/assets/${b.id}/dependencies`, 'POST', { expected_version: saved.version, additions: [edge(b.id, a.id)] }, 400)
    await msp.call(path, 'POST', { expected_version: saved.version, additions: [edge(a.id, a.id)] }, 400)
    await viewerB.call(path, 'GET', undefined, 404)
    same(await viewerA.call(path), saved)
    h.artifact('resource-dependencies', { id: a.id, added_edges: 1, current_version: saved.version, monitoring_applied: false })
  })

  await h.case('R06', '台账导出受理、筛选快照与后台实际准备', async () => {
    requireAssets()
    if (!state.metadata) h.block('A filtered synthetic switch is needed for this export test')
    const filters = { type: 'switch', status: 'unknown', site_id: state.assets[0].site_id, query: prefix }
    const selected = await msp.call(`/assets?${new URLSearchParams({ q: prefix, type: filters.type, status: filters.status, site_id: filters.site_id })}`)
    same(selected.items.map(row => row.id), [state.assets[0].id])
    state.exportSnapshot = await msp.call(metadataPath())
    const body = { expected_version: 0, mode: 'PREPARE_ONLY_NO_PUBLICATION', filters }, key = randomUUID()
    state.exportBody = body
    state.exportKey = key
    const receipt = await msp.call('/asset-export-jobs', 'POST', body, 202, { key })
    state.exportReceipt = receipt
    h.artifact('resource-export-accepted', { id: receipt.job_id, status: receipt.status, mode: receipt.mode })
    same(await msp.call('/asset-export-jobs', 'POST', body, 202, { key }), receipt)
    await msp.call('/asset-export-jobs', 'POST', { ...body, filters: { ...filters, query: `${prefix}_different` } }, 409, { key })
    await viewerA.call('/asset-export-jobs', 'POST', body, 403)
    await msp.call('/asset-export-jobs', 'POST', body, 403, { noCsrf: true })
    await padmin.call(exportPath(), 'GET', undefined, 404)
    const prepared = await poll(async () => {
      const value = await msp.call(exportPath())
      if (value.status === 'FAILED') throw new Error(`Synthetic export failed: ${value.error_code ?? 'UNKNOWN'}`)
      return value.prepared ? value : null
    }, { timeoutMs: 60000, intervalMs: 1000 }, 'The new export was not prepared by the existing queue within 60 seconds')
    if (!prepared) h.block('The new export has not been prepared by the existing queue within 60 seconds')
    state.prepared = prepared
    assert.equal(prepared.created_by, msp.session.user.id)
    assert.equal(prepared.tenant_id, msp.session.tenant.id)
    same(prepared.filters, filters)
    assert.equal(prepared.prepared.row_count, 1)
    assert.equal(prepared.worker_liveness, null)
    // These legacy preparation fields are intentionally not the publication fact.
    assert.equal(prepared.publication_status, 'NOT_IMPLEMENTED')
    h.artifact('resource-export-prepared', { id: receipt.job_id, status: prepared.status, row_count: 1, sha256: prepared.prepared.file_sha256, size: prepared.prepared.size_bytes })
  })

  await h.case('R07', 'MinIO显式发布、原回执与发布终态', async () => {
    requireExport()
    if (!state.prepared) h.block('The synthetic export has no prepared file')
    const before = await msp.call(`${exportPath()}/publication`)
    if (!before.publication_enabled) h.block('Export publication is not enabled in this environment')
    assert.equal(before.status, 'NOT_PUBLISHED')
    assert.equal(before.prepared, true)
    await msp.call(`${exportPath()}/content-grants`, 'POST', {}, 409)
    const body = { expected_version: before.version, file_sha256: before.file_sha256, action: 'PUBLISH_PREPARED_FILE' }
    await msp.call(`${exportPath()}/publication`, 'POST', body, 403, { noCsrf: true })
    await padmin.call(`${exportPath()}/publication`, 'GET', undefined, 404)
    const key = randomUUID()
    const published = await poll(async () => {
      const result = await msp.call(`${exportPath()}/publication`, 'POST', body, [200, 409], { key })
      if (result.status === 'PUBLISHED') return result
      assert.equal(result.code, 'EXPORT_PREPARATION_ACTIVE', 'Only an active preparation lease may be retried')
      return null
    }, { timeoutMs: 60000, intervalMs: 2000 }, 'The preparation lease did not end within 60 seconds; no active worker was taken over')
    if (!published) h.block('The existing preparation lease has not ended; this run does not take over active workers')
    state.published = published
    same(await msp.call(`${exportPath()}/publication`, 'POST', body, 200, { key }), published)
    const current = await msp.call(`${exportPath()}/publication`)
    assert.equal(current.status, 'PUBLISHED')
    assert.equal(current.download_grant_available, true)
    assert.equal(current.notification_sent, false)
    assert.equal(current.file_sha256, state.prepared.prepared.file_sha256)
    assert.equal((await msp.call(exportPath())).status, 'SUCCEEDED')
    same(await msp.call('/asset-export-jobs', 'POST', state.exportBody, 202, { key: state.exportKey }), state.exportReceipt)
    h.artifact('resource-export-published', { id: published.job_id, status: published.status, sha256: published.file_sha256, size: published.size_bytes, row_count: published.row_count })
  })

  await h.case('R08', '授权下载、真实XLSX筛选内容与签名防篡改', async () => {
    requirePublished()
    const key = randomUUID(), grant = await msp.call(`${exportPath()}/content-grants`, 'POST', {}, 200, { key })
    same(await msp.call(`${exportPath()}/content-grants`, 'POST', {}, 200, { key }), grant, 'Grant replay must keep its original expiration')
    const downloaded = await msp.download(grant.content_url)
    assert.equal(downloaded.bytes.length, grant.size_bytes)
    assert.equal(sha256(downloaded.bytes), grant.file_sha256)
    assert.equal(downloaded.headers.get('cache-control')?.includes('no-store'), true)
    assert.equal(downloaded.headers.get('x-content-type-options'), 'nosniff')
    assert.ok(downloaded.headers.get('content-disposition')?.includes('attachment'))
    const rows = readLedgerWorkbook(downloaded.bytes)
    assert.equal(rows.length, 2, 'The selected single asset must be the only data row')
    same(rows[0], ['设备名称', '设备类型', 'IP或域名', '品牌', '型号', '序列号', '站点', '位置', '负责人', '所属业务', '采购日期', '维保到期日', '超期服役日期', '采集方式', '备注', '维保状态'])
    const { asset, metadata } = state.exportSnapshot
    same(rows[1], [asset.name, asset.type, asset.management_ip, metadata.brand ?? '', metadata.model ?? '', metadata.serial_number ?? '', asset.site_id, metadata.location ?? '', metadata.owner_id ?? '', metadata.business_description ?? '', metadata.purchase_date ?? '', metadata.warranty_expiry ?? '', metadata.overdue_service_date ?? '', metadata.collection_method_note ?? '', metadata.note ?? '', ''])
    assert.ok(!rows.flat().includes(state.assets[1].name), 'The other synthetic type must be excluded')
    await padmin.download(grant.content_url, 404)
    await viewerB.download(grant.content_url, 403)
    const tampered = grant.content_url.slice(0, -1) + (grant.content_url.endsWith('A') ? 'B' : 'A')
    await msp.download(tampered, 404)
    const messagePage = await msp.call('/my-export-messages?page_size=50')
    const message = messagePage.items.find(row => row.job_id === state.exportReceipt.job_id)
    assert.ok(message, 'The published file must have its own inbox message')
    assert.equal(message.version, 0, 'Downloading must not mark the message as read')
    state.message = message
    h.artifact('resource-export-download', { id: grant.job_id, sha256: grant.file_sha256, size: grant.size_bytes, columns: 16, rows: rows.length - 1, formula_cells: 0, natural_expiration_retested: false })
  })

  await h.case('R09', '本人导出消息、未读筛选与首次已读幂等', async () => {
    requirePublished()
    const page = await msp.call('/my-export-messages?page_size=50&unread_only=true')
    const message = page.items.find(row => row.job_id === state.exportReceipt.job_id)
    assert.ok(message, 'This run has a new unread message')
    assert.equal(message.tenant_id, msp.session.tenant.id)
    assert.equal(message.recipient_id, msp.session.user.id)
    assert.equal(Date.parse(message.expires_at) - Date.parse(message.issued_at), 900000)
    assert.equal(message.version, 0)
    assert.equal(message.read_at, null)
    const path = `/my-export-messages/${message.job_id}/read`, body = { expected_version: 0 }, key = randomUUID()
    await msp.call(path, 'POST', body, 403, { noCsrf: true })
    await padmin.call(path, 'POST', body, 404)
    await viewerA.call('/my-export-messages', 'GET', undefined, 403)
    const read = await msp.call(path, 'POST', body, 200, { key })
    state.messageRead = read
    same(await msp.call(path, 'POST', body, 200, { key }), read)
    assert.equal(read.version, 1)
    const current = (await msp.call('/my-export-messages?page_size=50')).items.find(row => row.job_id === message.job_id)
    assert.ok(current)
    assert.equal(current.read_at, read.read_at)
    assert.equal(current.issued_at, message.issued_at)
    assert.equal(current.expires_at, message.expires_at)
    assert.equal(current.file_sha256, message.file_sha256)
    const unread = await msp.call('/my-export-messages?page_size=50&unread_only=true')
    assert.equal(unread.items.some(row => row.job_id === message.job_id), false)
    h.artifact('resource-export-inbox', { id: message.job_id, version: current.version, original_expiry_seconds: 900, repeated_read_preserved: true, external_delivery: false })
  })

  await h.case('R10', '本轮资料与导出的成功审计不因重放重复', async () => {
    requireAssets()
    assert.equal(padmin.session.tenant.id, msp.session.tenant.id)
    const verifyCount = async (id, action, count) => {
      const result = await padmin.call(`/audit/records?${new URLSearchParams({ resource_id: id, action, page_size: '10' })}`)
      assert.equal(result.has_more, false)
      assert.equal(result.items.filter(row => row.result === 'SUCCEEDED').length, count, `${action} successful audit count`)
    }
    for (const asset of state.assets) await verifyCount(asset.id, 'asset.create_draft', 1)
    if (state.metadataWrites) await verifyCount(state.assets[0].id, 'asset.manual_metadata.update', state.metadataWrites)
    if (state.warrantyWrites) await verifyCount(state.assets[0].id, 'asset.manual_warranty.update', state.warrantyWrites)
    if (state.published) {
      await verifyCount(state.exportReceipt.job_id, 'asset_export.publish', 1)
      await verifyCount(state.exportReceipt.job_id, 'export_inbox.store', 1)
    }
    if (state.messageRead) await verifyCount(state.exportReceipt.job_id, 'export_inbox.read', 1)
    h.artifact('resource-audit', { assets: state.assets.map(asset => asset.id), metadata_writes: state.metadataWrites, warranty_writes: state.warrantyWrites, publication_verified: !!state.published, explicit_read_verified: !!state.messageRead })
  })
}

const sha256 = bytes => createHash('sha256').update(bytes).digest('hex')

// Bounded parser for the actual XLSX produced by the project. Nothing is saved
// or extracted to disk, and formula cells or unexpected archive methods fail.
function readLedgerWorkbook(bytes) {
  assert.ok(Buffer.isBuffer(bytes) && bytes.length <= 16 * 1024 * 1024)
  let eocd = -1
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
    if (bytes.readUInt32LE(i) === 0x06054b50) { eocd = i; break }
  }
  assert.ok(eocd >= 0, 'XLSX must contain a ZIP central directory')
  const count = bytes.readUInt16LE(eocd + 10), files = new Map()
  assert.ok(count > 0 && count <= 100)
  let cursor = bytes.readUInt32LE(eocd + 16)
  for (let i = 0; i < count; i++) {
    assert.equal(bytes.readUInt32LE(cursor), 0x02014b50)
    const method = bytes.readUInt16LE(cursor + 10), compressedSize = bytes.readUInt32LE(cursor + 20)
    const size = bytes.readUInt32LE(cursor + 24), nameLength = bytes.readUInt16LE(cursor + 28)
    const extraLength = bytes.readUInt16LE(cursor + 30), commentLength = bytes.readUInt16LE(cursor + 32)
    const local = bytes.readUInt32LE(cursor + 42)
    const name = bytes.subarray(cursor + 46, cursor + 46 + nameLength).toString('utf8')
    cursor += 46 + nameLength + extraLength + commentLength
    if (!['xl/worksheets/sheet1.xml', 'xl/sharedStrings.xml'].includes(name)) continue
    assert.ok(size <= 2 * 1024 * 1024)
    assert.equal(bytes.readUInt32LE(local), 0x04034b50)
    const start = local + 30 + bytes.readUInt16LE(local + 26) + bytes.readUInt16LE(local + 28)
    assert.ok(start + compressedSize <= bytes.length)
    const compressed = bytes.subarray(start, start + compressedSize)
    assert.ok(method === 0 || method === 8)
    const data = method === 0 ? compressed : inflateRawSync(compressed, { maxOutputLength: 2 * 1024 * 1024 })
    assert.equal(data.length, size)
    files.set(name, data.toString('utf8'))
  }
  const sheet = files.get('xl/worksheets/sheet1.xml')
  assert.ok(sheet && !/<f(?:\s|>)/.test(sheet), 'All exported cells, including formula-like input, must remain text')
  const text = xml => [...xml.matchAll(/<t(?:\s[^>]*)?>([\s\S]*?)<\/t>/g)].map(match => xmlText(match[1])).join('')
  const shared = [...(files.get('xl/sharedStrings.xml') ?? '').matchAll(/<si(?:\s[^>]*)?>([\s\S]*?)<\/si>/g)].map(match => text(match[1]))
  const rows = []
  for (const row of sheet.matchAll(/<row\b[^>]*>([\s\S]*?)<\/row>/g)) {
    const cells = Array(16).fill('')
    for (const cell of row[1].matchAll(/<c\b([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/g)) {
      const reference = /\br="([A-Z]+)\d+"/.exec(cell[1])?.[1]
      assert.ok(reference)
      const column = [...reference].reduce((value, char) => value * 26 + char.charCodeAt(0) - 64, 0) - 1
      assert.ok(column >= 0 && column < 16)
      const content = cell[2] ?? '', value = /<v>([\s\S]*?)<\/v>/.exec(content)?.[1]
      if (/\bt="s"/.test(cell[1])) {
        assert.ok(value !== undefined && /^\d+$/.test(value) && Number(value) < shared.length)
        cells[column] = shared[Number(value)]
      } else cells[column] = /\bt="inlineStr"/.test(cell[1]) ? text(content) : xmlText(value ?? '')
    }
    rows.push(cells)
  }
  return rows
}

function xmlText(value) {
  return value.replace(/&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);/g, (_, entity) => {
    if (entity.startsWith('#x')) return String.fromCodePoint(Number.parseInt(entity.slice(2), 16))
    if (entity.startsWith('#')) return String.fromCodePoint(Number.parseInt(entity.slice(1), 10))
    return { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" }[entity]
  })
}
