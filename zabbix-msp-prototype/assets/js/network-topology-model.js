/* One independent, manually maintained topology per customer. No remote writes. */
(function (global) {
  'use strict';
  const clone = value => JSON.parse(JSON.stringify(value));
  const key = tenantId => 'msp.network-topology.v1.' + tenantId;
  const kinds = { internet: '互联网', router: '路由器', firewall: '防火墙', switch: '交换机', server: '服务器', database: '数据库', cloud: '云网络', other: '其他设备' };
  const statuses = { ok: '正常', warning: '告警', down: '中断', unknown: '未知', maintenance: '维护中' };
  function assetKind(asset) { return ['mysql', 'pgsql'].includes(asset.type) ? 'database' : ['switch', 'firewall'].includes(asset.type) ? asset.type : 'server'; }
  function seed(tenant, assets) {
    const nodes = [], edges = [], width = Math.max(650, tenant.sites.length * 300 + 40);
    const regions = tenant.sites.map((name, i) => ({ name, x: 20 + i * 300, y: 162, width: 280, height: 450 }));
    nodes.push({ id: 'internet', name: '互联网 / 运营商网络', kind: 'internet', site: '外部网络', assetId: '', ip: '', x: width / 2 - 84, y: 38, status: 'unknown', note: '外部网络示意节点，尚未配置独立探测。' });
    regions.forEach((region, index) => {
      const local = assets.filter(a => a.tenant === tenant.id && a.site === region.name);
      const firewall = local.find(a => a.type === 'firewall');
      const switches = local.filter(a => a.type === 'switch').slice(0, 2);
      const servers = local.filter(a => !['switch', 'firewall'].includes(a.type)).slice(0, 2);
      const addAsset = (a, x, y) => { const n = { id: 'node-' + a.id, name: a.name, assetId: a.id, kind: assetKind(a), site: a.site, ip: a.ip, x, y, status: a.status === 'problem' ? 'warning' : a.status === 'ok' ? 'ok' : 'unknown', note: '关联现有监控资产；连线为人工维护的示例关系。' }; nodes.push(n); return n.id; };
      const gateway = firewall ? addAsset(firewall, region.x + 56, 212) : (() => { const id = 'gateway-' + index; nodes.push({ id, name: /云/.test(region.name) ? 'VPC 网络网关' : '站点出口路由', kind: /云/.test(region.name) ? 'cloud' : 'router', assetId: '', site: region.name, ip: '', x: region.x + 56, y: 212, status: 'unknown', note: '手工节点，待核对设备与链路信息。' }); return id; })();
      const connect = (source, target, i, uplink) => edges.push({ id: 'edge-' + edges.length, source, target, name: uplink ? (/云/.test(region.name) ? '云专线' : '运营商专线') : '内部网络', sourcePort: uplink ? 'WAN1' : 'GE1/0/' + (i + 1), targetPort: uplink ? 'WAN' : 'GE0/0/1', bandwidth: uplink ? 1000 : 10000, vlan: uplink ? '' : String(100 + index * 10), type: uplink ? '专线' : '以太网', status: 'unknown', latency: null, loss: null, inMbps: null, outMbps: null, note: '示例连线；链路监测尚未绑定，状态及监控值为未知。' });
      connect('internet', gateway, 0, true);
      const switchIds = switches.map((a, i) => addAsset(a, region.x + (switches.length === 1 ? 56 : i * 132 + 10), 357));
      switchIds.forEach((id, i) => connect(gateway, id, i, false));
      servers.forEach((a, i) => { const id = addAsset(a, region.x + (servers.length === 1 ? 56 : i * 132 + 10), 504); connect(switchIds[i % (switchIds.length || 1)] || gateway, id, i, false); });
    });
    return { version: 1, tenantId: tenant.id, name: tenant.name + '网络拓扑', revision: 0, updatedAt: null, updatedBy: '', regions, nodes, edges, width, height: 650 };
  }
  function validate(graph, tenantId, assets) {
    if (!graph || graph.tenantId !== tenantId || graph.version !== 1) throw new Error('拓扑不属于当前客户或数据版本不兼容');
    if (!Array.isArray(graph.nodes) || !Array.isArray(graph.edges) || !Array.isArray(graph.regions)) throw new Error('拓扑数据结构无效');
    if (!String(graph.name || '').trim()) throw new Error('请填写拓扑名称');
    const ids = new Set(), linked = new Set();
    graph.nodes.forEach(n => {
      if (!n.id || ids.has(n.id)) throw new Error('节点编号重复');
      ids.add(n.id);
      if (!n.name?.trim() || !kinds[n.kind] || !statuses[n.status]) throw new Error('节点名称、类型或状态无效');
      if (!Number.isFinite(n.x) || !Number.isFinite(n.y) || n.x < 0 || n.y < 0) throw new Error('节点位置无效');
      if (n.assetId) {
        if (!assets.some(a => a.id === n.assetId && a.tenant === tenantId)) throw new Error('只能关联当前客户的资产');
        if (linked.has(n.assetId)) throw new Error('该资产已在当前拓扑中');
        linked.add(n.assetId);
      }
    });
    const edgeIds = new Set(), connections = new Set();
    graph.edges.forEach(e => {
      if (!e.id || edgeIds.has(e.id)) throw new Error('链路编号重复');
      edgeIds.add(e.id);
      if (e.source === e.target) throw new Error('链路两端不能是同一节点');
      if (!ids.has(e.source) || !ids.has(e.target)) throw new Error('链路引用了不存在的节点');
      if (!e.name?.trim() || !statuses[e.status] || !Number.isFinite(e.bandwidth) || e.bandwidth <= 0) throw new Error('请填写链路名称与有效带宽');
      if (e.vlan && (!/^\d+$/.test(e.vlan) || Number(e.vlan) < 1 || Number(e.vlan) > 4094)) throw new Error('VLAN 应为 1–4094 或留空');
      const pair = [e.source + ':' + e.sourcePort, e.target + ':' + e.targetPort].sort().join('|');
      if (connections.has(pair)) throw new Error('相同端口之间已存在链路');
      connections.add(pair);
    });
    return graph;
  }
  function removeNode(graph, id) { graph.nodes = graph.nodes.filter(n => n.id !== id); graph.edges = graph.edges.filter(e => e.source !== id && e.target !== id); }
  function load(storage, tenant, assets) {
    const raw = storage.getItem(key(tenant.id));
    if (!raw) return seed(tenant, assets);
    return validate(JSON.parse(raw), tenant.id, assets);
  }
  function save(storage, graph, tenantId, assets, actor) {
    validate(graph, tenantId, assets);
    const raw = storage.getItem(key(tenantId));
    const existing = raw ? JSON.parse(raw) : null;
    if ((existing?.revision || 0) !== graph.revision) throw new Error('另一页面已保存此拓扑，请重新加载最新版本后再修改');
    const saved = { ...clone(graph), revision: graph.revision + 1, updatedAt: new Date().toISOString(), updatedBy: actor };
    storage.setItem(key(tenantId), JSON.stringify(saved));
    return saved;
  }
  const model = { clone, key, kinds, statuses, assetKind, seed, validate, load, save, removeNode };
  if (typeof module !== 'undefined' && module.exports) module.exports = model;
  else global.NetworkTopologyModel = model;
})(typeof window !== 'undefined' ? window : globalThis);
