import assert from 'node:assert/strict'

export async function runReadmodels(h) {
  const a = h.clients.padmin, m = h.clients.msp, v = h.clients.viewerA, other = h.clients.viewerB
  await h.case('READ-01', '租户登记、权限能力与本人设置的真实读取', async () => {
    const inventory = await a.call('/tenants/inventory?page_size=10')
    assert.ok(Array.isArray(inventory.items))
    await m.call('/tenants/inventory', 'GET', undefined, 403)
    await v.call('/tenants/inventory', 'GET', undefined, 403)
    await a.call('/authorization/capability-catalog')
    await a.call('/identity/providers'); await a.call('/account')
    await m.call('/my/notification-preferences'); await m.call('/my/worklogs')
    h.artifact('read-model-boundaries', { inventory_rows: inventory.items.length, notification_delivery_tested: false, capacity_is_not_quota: true })
  })
  await h.case('READ-02', '总览三种范围、六指标与六面板保持未知', async () => {
    for (const window of ['HOURS_24', 'DAYS_7', 'DAYS_30']) {
      const data = await v.call('/dashboard?window=' + window)
      assert.equal(data.tenant_id, v.session.tenant.id); assert.equal(data.window, window)
      assert.equal(data.kpis.length, 6); assert.equal(data.panels.length, 6)
      assert.ok(data.kpis.every(row => row.value === null && row.state === 'UNKNOWN'))
      assert.ok(data.panels.every(row => row.items === null && row.state === 'UNKNOWN'))
      assert.equal(data.writes_performed, false); assert.equal(data.metric_samples_loaded, false)
    }
    await a.call('/dashboard?window=INVALID', 'GET', undefined, 400)
    h.artifact('dashboard-scope', { windows: 3, kpis: 6, panels: 6, formal_metric_acceptance: 'BLOCKED_NOT_IMPLEMENTED' })
  })
  await h.case('READ-03', '全局搜索与审计筛选、错误输入拒绝', async () => {
    const result = await a.call('/search?q=' + encodeURIComponent('SYNTHETIC_ACCEPTANCE'))
    assert.equal(result.tenant_id, a.session.tenant.id)
    await a.call('/search?q=x&q=y', 'GET', undefined, 400)
    await a.call('/search?q=', 'GET', undefined, 400)
    await a.call('/audit/records?page_size=10&action=asset.create_draft')
    await a.call('/audit/integrity'); await v.call('/audit/records', 'GET', undefined, 403)
  })
  await h.case('READ-04', '四设备基础监控与真实历史、跨租户隔离', async () => {
    const current = await v.call('/network-monitoring')
    assert.equal(current.tenant_id, v.session.tenant.id)
    if (!current.devices.length) h.block('NO_BOUND_NETWORK_DEVICES')
    for (const device of current.devices) {
      await v.call('/network-monitoring/' + device.asset_id + '/history?metric=latency&window=HOUR_1')
      await other.call('/network-monitoring/' + device.asset_id + '/history', 'GET', undefined, 404)
    }
    const resourceDevice = current.devices.find(row => row.vendor_metrics_status === 'PARTIAL_API')
    if (!resourceDevice) h.block('NO_RESOURCE_API_DEVICE')
    await v.call('/network-monitoring/' + resourceDevice.asset_id + '/resources')
    for (const metric of ['cpu','memory']) await v.call('/network-monitoring/' + resourceDevice.asset_id + '/resources/history?metric=' + metric + '&window=HOUR_1')
    await other.call('/network-monitoring/' + resourceDevice.asset_id + '/resources', 'GET', undefined, 404)
    await a.call('/network-monitoring?hostid=1', 'GET', undefined, 400)
    h.artifact('monitoring-read', { bound_devices: current.devices.length, actual_resource_device_id: resourceDevice.asset_id, formal_alert_sla_tested: false })
  })
}
