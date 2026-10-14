import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

/** Current-project-account HTTP acceptance. No fixture, device command or notification. */
export async function runServices(h) {
  const { padmin: admin, msp, tadmin, viewerA: customer, viewerB: foreign, reviewer } = h.clients;
  const marker = h.prefix + '_SERVICES';
  const owned = [];
  let ticket, foreignTicket, assetId, template, worksheet, source, collection;
  const keep = (kind, row, labelField = 'name') => {
    owned.push({ kind, id: row.id, labelField, tenantId: row.tenant_id });
    h.artifact('service-resource', { kind, id: row.id, tenant_id: row.tenant_id, version: row.version, synthetic_prefix: marker });
    return row;
  };
  const requireTicket = () => { if (!ticket?.id) h.block('PREREQUISITE:SYNTHETIC_TICKET_NOT_CREATED'); };
  const requireAsset = async () => {
    if (assetId) return assetId;
    if (h.assetId) {
      const row = await admin.call('/assets/' + h.assetId);
      assert.equal(row.tenant_id, admin.session.tenant.id);
      assetId = row.id;
    } else {
      const page = await admin.call('/assets?page_size=10');
      if (!page.items?.length) h.block('PREREQUISITE:REGISTERED_ASSET_NOT_AVAILABLE');
      assetId = page.items[0].id;
    }
    h.artifact('service-asset-reference', { asset_id: assetId, use: 'draft_reference_only', asset_modified: false, device_actions: 0 });
    return assetId;
  };
  const draftOnly = row => {
    assert.equal(row.effective, false);
    assert.equal(row.device_execution_allowed, false);
    assert.equal(row.lifecycle_status, 'DRAFT');
  };
  const createTicket = async (client, suffix) => {
    const body = { title: marker + '_' + suffix, source: 'customer_report', description: '逐功能验收虚构报障；不代表真实事故，不请求发送通知。', category: '网络不通', impact_scope: 'individual', contact_name: '虚构验收联系人', contact_phone: '+00 000', urgency: 'normal', attachment_ids: [] };
    const key = randomUUID();
    const receipt = await client.call('/tickets', 'POST', body, 202, { key });
    h.artifact('service-ticket-operation', { operation_id: receipt.operation_id, synthetic_prefix: marker });
    assert.deepEqual(await client.call('/tickets', 'POST', body, 202, { key }), receipt);
    const operation = await client.call('/operations/' + receipt.operation_id);
    assert.match(operation.result_ref, /^\/api\/v1\/tickets\/[0-9a-f-]+$/);
    const result = await client.call(operation.result_ref.replace('/api/v1', ''));
    assert.equal(result.title, body.title);
    assert.equal(result.tenant_id, client.session.tenant.id);
    assert.equal(result.reporter_id, client.session.user.id);
    assert.equal(result.registration_status, 'REGISTERED');
    for (const field of ['state', 'priority', 'response_due_at', 'recovery_due_at']) assert.equal(result[field], null);
    h.artifact('service-ticket', { id: result.id, tenant_id: result.tenant_id, operation_id: result.operation_id, durable_registration: true, formal_ticket_transition_tested: false });
    return result;
  };

  await h.case('SVC-01', '报障耐久登记、原请求重放和租户隔离', async () => {
    assert.equal(customer.session.tenant.id, admin.session.tenant.id, '报障人与管理员必须在同一测试租户');
    assert.equal(msp.session.tenant.id, admin.session.tenant.id, '工程师必须在同一测试租户');
    assert.notEqual(foreign.session.tenant.id, customer.session.tenant.id, '跨租户验收需要不同测试租户');
    ticket = await createTicket(customer, 'TICKET');
    foreignTicket = await createTicket(foreign, 'FOREIGN_TICKET');
    assert.equal((await msp.call('/tickets/' + ticket.id)).id, ticket.id);
    await foreign.call('/tickets/' + ticket.id, 'GET', undefined, 404);
    await msp.call('/tickets/' + foreignTicket.id, 'GET', undefined, 404);
    const page = await customer.call('/tickets?page_size=10&q=' + encodeURIComponent(marker));
    assert.ok(page.items.some(row => row.id === ticket.id));
    assert.ok(page.items.every(row => row.tenant_id === customer.session.tenant.id && row.reporter_id === customer.session.user.id));
    await customer.call('/tickets', 'POST', { source: 'customer_report', description: 'invalid' }, 400);
  });

  await h.case('SVC-02', '客户评论追加、内部备注隔离与幂等', async () => {
    requireTicket();
    const path = '/tickets/' + ticket.id + '/comments';
    const body = { expected_ticket_version: ticket.version, text: marker + ' 客户可见原文😀\n第二行' }, key = randomUUID();
    const comment = await msp.call(path, 'POST', body, 200, { key });
    assert.equal(comment.text, body.text);
    assert.equal(comment.author_kind, 'ENGINEER');
    assert.equal(comment.visibility, 'REPORTER_AND_INTERNAL');
    assert.equal(comment.immutable, true);
    assert.equal(comment.ticket_state_changed, false);
    assert.equal(comment.sla_changed, false);
    assert.equal(Object.hasOwn(comment, 'actor_id'), false);
    assert.deepEqual(await msp.call(path, 'POST', body, 200, { key }), comment);
    assert.deepEqual(await customer.call(path + '/' + comment.id), comment);
    await msp.call(path, 'POST', { ...body, text: marker + '_changed' }, 409, { key });
    const reply = await customer.call(path, 'POST', { ...body, text: marker + ' 报障人自述回复' });
    assert.equal(reply.author_kind, 'REPORTER');
    await tadmin.call(path, 'GET', undefined, 404);
    await foreign.call(path, 'GET', undefined, 404);
    await customer.call(path, 'POST', body, 403, { noCsrf: true });
    await customer.call(path, 'POST', { ...body, expected_ticket_version: ticket.version + 1 }, 409);
    await customer.call(path, 'POST', { ...body, text: '' }, 400);
    h.artifact('service-comments', { ticket_id: ticket.id, comment_ids: [comment.id, reply.id], notifications_sent: 0 });
  });

  await h.case('SVC-03', '不可变内部备注与客户不可见', async () => {
    requireTicket();
    const path = '/tickets/' + ticket.id + '/internal-notes';
    const body = { expected_ticket_version: ticket.version, text: marker + ' 内部备注，客户不可见😀\n保留原文' }, key = randomUUID();
    const note = await msp.call(path, 'POST', body, 200, { key });
    assert.equal(note.text, body.text);
    assert.equal(note.actor_id, msp.session.user.id);
    assert.equal(note.visibility, 'INTERNAL');
    assert.equal(note.immutable, true);
    assert.equal(note.ticket_state_changed, false);
    assert.deepEqual(await msp.call(path, 'POST', body, 200, { key }), note);
    assert.deepEqual(await reviewer.call(path + '/' + note.id), note);
    for (const client of [customer, tadmin, foreign]) await client.call(path, 'GET', undefined, 403);
    const comments = await customer.call('/tickets/' + ticket.id + '/comments?page_size=15');
    assert.equal(comments.items.some(row => row.id === note.id || row.text === body.text), false);
    assert.equal((await customer.call('/tickets/' + ticket.id)).version, ticket.version);
    await msp.call(path, 'POST', { ...body, expected_ticket_version: ticket.version + 1 }, 409);
    await msp.call(path, 'POST', body, 403, { noCsrf: true });
    h.artifact('service-internal-note', { ticket_id: ticket.id, note_id: note.id, customer_visibility: false });
  });

  await h.case('SVC-04', '本人工时新增、零时长修订、历史和越权保护', async () => {
    requireTicket();
    const path = '/tickets/' + ticket.id + '/worklogs';
    const facts = { kind: 'REMOTE', started_at: '2026-10-01T08:00:00+08:00', ended_at: '2026-10-03T09:00:00+08:00', note: marker + ' 虚构工时49小时' };
    const body = { expected_ticket_version: ticket.version, ...facts }, key = randomUUID();
    const one = await msp.call(path, 'POST', body, 200, { key });
    assert.equal(one.elapsed_millis, 49 * 3600000);
    assert.equal(one.owner_id, msp.session.user.id);
    assert.equal(one.verification_status, 'SELF_REPORTED');
    for (const field of ['default_minutes', 'labor_cost', 'transport_cost', 'gross_margin']) assert.equal(one[field], null);
    assert.deepEqual(await msp.call(path, 'POST', body, 200, { key }), one);
    const update = { expected_version: one.version, ...facts, kind: 'ONSITE', ended_at: facts.started_at, note: marker + ' 明确实填0秒' };
    const two = await msp.call(path + '/' + one.id, 'PATCH', update);
    assert.equal(two.elapsed_millis, 0); assert.equal(two.version, 2);
    const history = await msp.call(path + '/' + one.id + '/history?page_size=15');
    assert.deepEqual(history.items.map(row => row.version), [2, 1]);
    assert.equal(history.items[1].elapsed_millis, 49 * 3600000);
    await msp.call(path + '/' + one.id, 'PATCH', update, 409);
    await msp.call(path, 'POST', { ...body, ended_at: '2026-09-01T00:00:00Z' }, 400);
    for (const client of [admin, customer, tadmin, reviewer]) await client.call(path, 'GET', undefined, 403);
    if (foreignTicket) await msp.call('/tickets/' + foreignTicket.id + '/worklogs', 'GET', undefined, 404);
    const own = await msp.call('/my/worklogs?page_size=15&ticket_id=' + ticket.id);
    assert.ok(own.items.some(row => row.id === one.id && row.elapsed_millis === 0));
    assert.ok(own.items.every(row => row.owner_id === msp.session.user.id && row.ticket_id === ticket.id));
    h.artifact('service-worklog', { id: one.id, ticket_id: ticket.id, versions: [1, 2], history_retained: true, financial_values: null });
  });

  const contactFacts = { name: marker + '_CONTACT', roles: ['TECHNICAL_CONTACT'], mobile: null, email: 'acceptance@example.test', wecom_user_id: null, preferred_priorities: null, quiet_window: null, enabled: false };
  await h.case('SVC-05', '联系人格式预览保留错误行且不写入', async () => {
    const result = await admin.call('/contacts/import-preview', 'POST', { rows: [contactFacts, contactFacts, { ...contactFacts, enabled: 'false' }] });
    assert.equal(result.valid_rows, 2); assert.equal(result.invalid_rows, 1); assert.equal(result.identical_rows, 1);
    assert.deepEqual(result.rows.map(row => row.row_number), [1, 2, 3]);
    for (const field of ['can_import', 'writes_performed', 'notification_dispatched', 'preview_saved', 'job_created']) assert.equal(result[field], false);
    assert.equal(result.rows[2].contact, null);
    await customer.call('/contacts/import-preview', 'POST', { rows: [contactFacts] }, 403);
    await admin.call('/contacts/import-preview', 'POST', { rows: [contactFacts] }, 403, { noCsrf: true });
  });

  await h.case('SVC-06', '联系人新增、修订、原回执、归档与恢复', async () => {
    const path = '/contacts', body = { expected_version: 0, ...contactFacts }, key = randomUUID();
    // No existing decision-maker is edited. An absent required baseline remains a real prerequisite failure.
    const created = await admin.call(path, 'POST', body, [200, 409], { key });
    if (created.code === 'LAST_ENABLED_DECISION_MAKER') h.block('PREREQUISITE:ENABLED_DECISION_MAKER_ABSENT_NO_EXISTING_CONTACT_MODIFIED');
    assert.ok(created.id, 'CONTACT_CREATE_REJECTED');
    const one = keep(path, created);
    assert.equal(one.enabled, false); assert.equal(one.delivery_eligible, false); assert.equal(one.notification_dispatched, false);
    assert.deepEqual(await admin.call(path, 'POST', body, 200, { key }), one);
    const changed = { expected_version: one.version, ...contactFacts, name: marker + '_CONTACT_UPDATED', preferred_priorities: [], quiet_window: { enabled: null, start: null, end: null } };
    const two = await tadmin.call(path + '/' + one.id, 'PATCH', changed);
    assert.equal(two.version, 2); assert.deepEqual(two.preferred_priorities, []);
    assert.deepEqual(await admin.call(path, 'POST', body, 200, { key }), one);
    await admin.call(path + '/' + one.id, 'PATCH', changed, 409);
    const archived = await admin.call(path + '/' + one.id + '/archive', 'POST', { expected_version: 2 });
    assert.equal(archived.archived, true); assert.equal(archived.enabled, false);
    const restored = await admin.call(path + '/' + one.id + '/restore', 'POST', { ...changed, expected_version: archived.version });
    assert.equal(restored.archived, false); assert.equal(restored.enabled, false); assert.equal(restored.version, 4);
    const history = await admin.call(path + '/' + one.id + '/history?page_size=15');
    assert.deepEqual(history.items.map(row => row.version), [4, 3, 2, 1]);
    for (const client of [customer, msp]) await client.call(path, 'GET', undefined, 403);
    await admin.call(path, 'POST', body, 403, { noCsrf: true });
    await admin.call(path, 'POST', { ...body, mobile: '13800000000' }, 400);
  });

  await h.case('SVC-07', '备份计划草稿修订、幂等与归档恢复', async () => {
    const target = await requireAsset(), path = '/backup-plan-drafts';
    const body = { expected_version: 0, name: marker + '_BACKUP', target_asset_ids: [target], daily_local_time: '02:00', credential_ref: null, requested_enabled: false, note: null }, key = randomUUID();
    const one = keep(path, await admin.call(path, 'POST', body, 200, { key }));
    draftOnly(one); assert.equal(one.enabled, false); assert.equal(one.next_execution_at, null);
    assert.deepEqual(await admin.call(path, 'POST', body, 200, { key }), one);
    const changed = { ...body, expected_version: 1, name: marker + '_BACKUP_UPDATED', daily_local_time: '03:15', note: '' };
    const two = await admin.call(path + '/' + one.id, 'PATCH', changed);
    assert.equal(two.version, 2); assert.equal(two.note, ''); draftOnly(two);
    await admin.call(path + '/' + one.id, 'PATCH', changed, 409);
    const archived = await admin.call(path + '/' + one.id + '/archive', 'POST', { expected_version: 2 });
    assert.equal(archived.archived, true);
    const restored = await admin.call(path + '/' + one.id + '/restore', 'POST', { expected_version: 3 });
    assert.equal(restored.version, 4); assert.equal(restored.archived, false); draftOnly(restored);
    assert.deepEqual((await admin.call(path + '/' + one.id + '/history')).items.map(row => row.version), [4, 3, 2, 1]);
    await customer.call(path, 'GET', undefined, 403);
    await admin.call(path, 'POST', { ...body, target_asset_ids: [randomUUID()] }, 404);
    await admin.call(path, 'POST', { ...body, enabled: true }, 400);
  });

  await h.case('SVC-08', '变更报备草稿绑定、修订与审批边界', async () => {
    const target = await requireAsset(), path = '/change-notice-drafts';
    const body = { expected_version: 0, change_summary: marker + '_CHANGE', asset_ids: [target], planned_start_at: '2026-10-01T08:00:00Z', planned_end_at: null, ticket_id: ticket?.id ?? null }, key = randomUUID();
    const one = keep(path, await admin.call(path, 'POST', body, 200, { key }), 'change_summary');
    draftOnly(one); assert.equal(one.approval_status, null); assert.equal(one.alert_suppression_allowed, false);
    assert.deepEqual(await admin.call(path, 'POST', body, 200, { key }), one);
    const changed = { ...body, expected_version: 1, change_summary: marker + '_CHANGE_UPDATED', planned_end_at: '2026-10-01T09:00:00Z' };
    const two = await admin.call(path + '/' + one.id, 'PATCH', changed);
    assert.equal(two.version, 2); assert.equal(two.applicant_id, one.applicant_id); draftOnly(two);
    await admin.call(path + '/' + one.id, 'PATCH', changed, 409);
    const archived = await admin.call(path + '/' + one.id + '/archive', 'POST', { expected_version: 2 });
    assert.equal(archived.archived, true);
    const restored = await admin.call(path + '/' + one.id + '/restore', 'POST', { expected_version: 3 });
    assert.equal(restored.version, 4); assert.equal(restored.archived, false); draftOnly(restored);
    assert.deepEqual((await admin.call(path + '/' + one.id + '/history')).items.map(row => row.version), [4, 3, 2, 1]);
    await msp.call(path, 'GET', undefined, 403);
    await admin.call(path, 'POST', { ...body, approval_status: 'APPROVED' }, 400);
  });

  const templateBody = { expected_version: 0, device_type: 'acceptance_custom', name: marker + '_TEMPLATE', items: [{ key: 'manual', section: '虚构人工检查', label: '显式观察项', instruction: null }, { key: 'unknown', section: '虚构人工检查', label: '未填写项', instruction: '' }] };
  await h.case('SVC-09', '巡检模板候选、原文和空值保留', async () => {
    const path = '/inspection-templates', key = randomUUID();
    template = keep(path, await admin.call(path, 'POST', templateBody, 200, { key }));
    assert.equal(template.approval_status, 'UNVERIFIED'); assert.equal(template.version, 1);
    assert.deepEqual(template.items, templateBody.items);
    assert.deepEqual(await admin.call(path, 'POST', templateBody, 200, { key }), template);
    assert.deepEqual(await reviewer.call(path + '/' + template.id), template);
    await admin.call(path, 'POST', { ...templateBody, items: [templateBody.items[0], templateBody.items[0]] }, 400);
    await customer.call(path, 'GET', undefined, 403);
    await admin.call(path, 'POST', templateBody, 403, { noCsrf: true });
  });

  await h.case('SVC-10', '人工巡检执行单、冻结模板和不可变来源修订', async () => {
    if (!template?.id) h.block('PREREQUISITE:SYNTHETIC_TEMPLATE_NOT_CREATED');
    const target = await requireAsset(), path = '/inspection-worksheets';
    const body = { expected_version: 0, asset_id: target, template_id: template.id, template_version: template.version, observations: [{ key: 'manual', result: 'ATTENTION', note: marker + ' 自述观察' }], reported_performed_at: null }, key = randomUUID();
    worksheet = keep(path, await admin.call(path, 'POST', body, 200, { key }), 'template_name');
    assert.equal(worksheet.summary.answered, 1); assert.equal(worksheet.summary.unanswered, 1);
    assert.equal(worksheet.reported_performed_at, null); assert.equal(worksheet.report_published, false);
    assert.equal(worksheet.active_scan_performed, false); assert.equal(worksheet.verification_status, 'SELF_REPORTED_UNVERIFIED');
    assert.deepEqual(await admin.call(path, 'POST', body, 200, { key }), worksheet);
    source = await admin.call(path + '/' + worksheet.id + '/revisions/1');
    assert.equal(source.worksheet_id, worksheet.id); assert.equal(source.evidence_access_granted, false);
    assert.match(source.source_snapshot_sha256, /^[0-9a-f]{64}$/);
    const changed = { expected_version: 1, observations: [{ key: 'manual', result: 'NORMAL', note: '' }, { key: 'unknown', result: 'REQUIRES_ACTION', note: null }], reported_performed_at: '2026-10-01T08:00:00+08:00' };
    const two = await admin.call(path + '/' + worksheet.id, 'PATCH', changed);
    assert.equal(two.version, 2); assert.equal(two.summary.unanswered, 0); assert.equal(two.template_version, template.version);
    await admin.call(path + '/' + worksheet.id, 'PATCH', changed, 409);
    assert.deepEqual(await admin.call(path + '/' + worksheet.id + '/revisions/1'), source);
    const templateTwo = await admin.call('/inspection-templates/' + template.id, 'PATCH', { ...templateBody, expected_version: 1, name: marker + '_TEMPLATE_UPDATED' });
    assert.equal(templateTwo.version, 2);
    assert.equal((await admin.call(path + '/' + worksheet.id)).template_name, template.name);
    await admin.call(path, 'POST', body, 409);
    await customer.call(path, 'GET', undefined, 403);
    h.artifact('service-inspection-source', { worksheet_id: worksheet.id, version: source.version, source_sha256: source.source_snapshot_sha256, frozen_template_version: template.version });
  });

  await h.case('SVC-11', '巡检来源集合显式冻结、摘要冲突和报告未发布', async () => {
    if (!source?.worksheet_id) h.block('PREREQUISITE:SYNTHETIC_WORKSHEET_SOURCE_NOT_AVAILABLE');
    const path = '/inspection-source-collections', key = randomUUID();
    const selected = { worksheet_id: source.worksheet_id, version: source.version, expected_source_sha256: source.source_snapshot_sha256 };
    const body = { expected_version: 0, label: marker + '_SOURCE_COLLECTION', sources: [selected] };
    collection = await admin.call(path, 'POST', body, 200, { key });
    h.artifact('service-source-collection', { id: collection.id, immutable_retained: true, source_worksheet_id: source.worksheet_id });
    assert.equal(collection.projection, 'METADATA_ONLY'); assert.equal(collection.report_published, false);
    assert.deepEqual(await admin.call(path, 'POST', body, 200, { key }), collection);
    const detail = await admin.call(path + '/' + collection.id);
    assert.deepEqual(detail.sources, [source]); assert.equal(detail.formal_report_generatable, false); assert.equal(detail.tenant_asset_population, null);
    assert.equal(detail.document_integrity, 'VERIFIED_SERVER_SIDE'); assert.equal(detail.evidence_access_granted, false);
    await admin.call(path, 'POST', { ...body, sources: [{ ...selected, expected_source_sha256: '0'.repeat(64) }] }, 409);
    await admin.call(path, 'POST', { ...body, sources: [selected, selected] }, 400);
    await msp.call(path, 'GET', undefined, 403);
  });

  await h.case('SVC-12', 'SLA显式场景只读对照、未知值和排除规则', async () => {
    const row = { scenario_ref: marker + '_SLA_MET', priority: 'P1', exclusion: 'NONE', exclusion_approval: null, sla_snapshot: { id: marker + '_ASSUMED_SNAPSHOT', version: 1 }, response_due_at: '2026-10-01T01:00:00Z', recovery_due_at: '2026-10-01T04:00:00Z', response_actual_at: '2026-10-01T01:00:00Z', recovery_actual_at: '2026-10-01T04:00:00Z' };
    const body = { population_complete: true, tickets: [row, { ...row, scenario_ref: marker + '_SLA_LATE', exclusion: 'PENDING_APPROVAL', response_actual_at: '2026-10-01T01:00:00.001Z' }, { ...row, scenario_ref: marker + '_SLA_P4', priority: 'P4' }] };
    const result = await admin.call('/sla/compliance-scenarios', 'POST', body);
    assert.equal(result.status, 'CALCULATED'); assert.deepEqual(result.scenario_achievement, { numerator: 1, denominator: 2 });
    assert.equal(result.items[1].unapproved_exclusion_ignored, true);
    assert.equal(result.items[2].scenario_outcome, 'NOT_APPLICABLE');
    assert.equal(result.effective, false); assert.equal(result.effective_achievement, null); assert.equal(result.compensation_amount, null);
    const unknown = await admin.call('/sla/compliance-scenarios', 'POST', { population_complete: null, tickets: [] });
    assert.equal(unknown.status, 'UNRESOLVED'); assert.equal(unknown.scenario_achievement, null);
    await customer.call('/sla/compliance-scenarios', 'POST', body, 403);
    await admin.call('/sla/compliance-scenarios', 'POST', { ...body, tickets: [row, row] }, 400);
    h.artifact('service-sla-boundary', { source: result.source, actual_ticket_population_loaded: false, formal_sla_achievement: null, compensation_amount: null });
  });

  // Cleanup archives only IDs returned by this run and re-checks prefix plus tenant before every write.
  for (const record of owned.reverse()) {
    await h.case('SVC-CLEAN-' + record.id, '归档本轮虚构对象：' + record.kind, async () => {
      const row = await admin.call(record.kind + '/' + record.id);
      assert.equal(row.tenant_id, record.tenantId);
      assert.equal(row.tenant_id, admin.session.tenant.id);
      assert.ok(typeof row[record.labelField] === 'string' && row[record.labelField].startsWith(marker), '拒绝归档非本轮对象');
      if (!row.archived) {
        const archived = await admin.call(record.kind + '/' + record.id + '/archive', 'POST', { expected_version: row.version });
        assert.equal(archived.archived, true);
      }
      const current = await admin.call(record.kind + '/' + record.id);
      assert.equal(current.archived, true);
      h.artifact('service-cleanup', { kind: record.kind, id: record.id, archived: true, history_retained: true });
    });
  }
  if (collection?.id && source) {
    await h.case('SVC-13', '来源归档后巡检集合仍保持原修订', async () => {
      const detail = await admin.call('/inspection-source-collections/' + collection.id);
      assert.deepEqual(detail.sources, [source]);
      assert.equal(detail.report_published, false);
    });
  }
}
