(function () {
  'use strict';
  const M = NetworkTopologyModel, { esc, icon } = App;
  const sessions = new Map();
  const iconMap = { internet: 'globe', router: 'router', firewall: 'shield', switch: 'network', server: 'server', database: 'database', cloud: 'cloud', other: 'box' };
  let root, currentId, session, canvas, scene, observer, search = '', site = 'all';
  let view = { scale: 1, x: 0, y: 0 }, drag = null;
  const canEdit = () => App.can('tadmin', 'msp', 'padmin') && App.state.tenant === currentId;
  const button = (text, action, ic, cls = '', extra = '') => `<button type="button" class="btn ${cls}" data-nt="${action}" ${extra}>${ic ? icon(ic) : ''}${text}</button>`;
  const options = (items, value) => items.map(([id, name]) => `<option value="${esc(id)}" ${id === value ? 'selected' : ''}>${esc(name)}</option>`).join('');
  const field = (title, name, value = '', type = 'text', extra = '') => `<div class="field"><label for="nt-${name}">${title}</label><input id="nt-${name}" class="input" name="${name}" type="${type}" value="${esc(value)}" ${extra}></div>`;
  const choice = (title, name, items, value) => `<div class="field"><label for="nt-${name}">${title}</label><select id="nt-${name}" class="select" name="${name}">${options(items, value)}</select></div>`;
  const getAsset = n => n.assetId ? DATA.assets.find(a => a.id === n.assetId && a.tenant === currentId) : null;
  function nodeStatus(n) { const a = getAsset(n); return a ? ({ problem: 'warning', ok: 'ok', maintenance: 'maintenance' }[a.status] || 'unknown') : n.status; }
  function getSession(tenant) {
    if (!sessions.has(tenant.id)) { const graph = M.load(localStorage, tenant, DATA.assets); sessions.set(tenant.id, { graph, baseline: M.clone(graph), edit: false, dirty: false, selection: null }); }
    return sessions.get(tenant.id);
  }
  function audit(action, desc) {
    const record = { id: 'nt-' + App.uid(), ts: Date.now(), actor: App.USERS[App.state.role].name, role: App.state.role, tenant: currentId, action, desc, object: session.graph.name, result: 'success', ip: '本地原型', trace: 'nt-' + App.uid(), payloadHash: '本地演示记录' };
    const records = App.store.get('network-topology.audit', []);
    localStorage.setItem('msp.network-topology.audit', JSON.stringify([record, ...(Array.isArray(records) ? records : [])].slice(0, 100)));
    DATA.audit.unshift(record);
  }
  function overview() {
    root.innerHTML = `<div class="page-head"><div><h1>客户网络拓扑</h1><div class="sub">每个客户独立维护网络设备、站点与连接关系。选择客户后查看或编辑。</div></div><span class="badge info">${DATA.tenants.length} 个客户 · 独立拓扑</span></div><div class="nt-overview">${DATA.tenants.map(t => {
      let g, error; try { g = getSession(t).graph; } catch (e) { error = e.message; }
      const preview = `<svg viewBox="0 0 240 112" aria-hidden="true"><g fill="none" stroke="#abc2e2" stroke-width="2"><path d="M120 25V52H42V76M120 52V76M120 52H198V76"/></g><g fill="white" stroke="#b7cce9"><rect x="94" y="4" width="52" height="30" rx="7"/><rect x="16" y="76" width="52" height="30" rx="7"/><rect x="94" y="76" width="52" height="30" rx="7"/><rect x="172" y="76" width="52" height="30" rx="7"/></g><g fill="none" stroke="#5e8fc9" stroke-width="2"><path d="M110 19h20M115 13v12M125 13v12M28 86h28M28 94h28M106 86h28M106 94h28M184 86h28M184 94h28"/></g></svg>`;
      return `<section class="card nt-customer-card"><div class="nt-customer-preview">${preview}</div><div class="card-body"><div class="row between"><h3>${esc(t.name)}</h3>${App.badge(g?.revision ? 'ok' : 'neutral', g?.revision ? '已维护' : '示例拓扑')}</div><p class="small muted mt8">${esc(t.sites.join(' · '))}</p>${error ? `<div class="callout danger mt8">${esc(error)}</div>` : `<div class="nt-card-stats"><span class="muted small">设备节点<strong>${g.nodes.length}</strong></span><span class="muted small">网络链路<strong>${g.edges.length}</strong></span><span class="muted small">站点<strong>${g.regions.length}</strong></span></div>`}<div class="nt-card-foot"><span class="xs muted">${g?.updatedAt ? '更新于 ' + App.fmt.dt(g.updatedAt) : '待核对网络关系'}</span>${button('查看拓扑', 'customer', 'arrow-right', 'sm primary', `data-id="${t.id}"`)}</div></div></section>`;
    }).join('')}</div><p class="nt-meta">示例关系用于原型演示；编辑后的拓扑按客户分别保存在当前浏览器中。</p>`;
    root.onclick = event => { const b = event.target.closest('[data-nt="customer"]'); if (b) App.setTenant(b.dataset.id); };
  }
  function render(target) {
    root = target;
    // Keep deep links consistent with the shell, including after a page refresh.
    const url = new URL(location.href);
    url.searchParams.set('tenant', App.state.tenant);
    url.searchParams.set('role', App.state.role);
    try { history.replaceState(null, '', url); } catch (_) { /* file URL hosts may restrict history updates */ }
    if (observer) observer.disconnect();
    if (App.state.tenant === 'all') { currentId = null; session = null; overview(); return; }
    const tenant = App.tenant();
    if (currentId !== tenant.id) { currentId = tenant.id; search = ''; site = 'all'; }
    try { session = getSession(tenant); } catch (error) { root.innerHTML = `<div class="page-head"><h1>网络拓扑 · ${esc(tenant.name)}</h1></div><div class="callout danger">${icon('alert-triangle')}<div>无法读取已保存的拓扑：${esc(error.message)}。已有数据保持不变，请检查浏览器存储。</div></div>`; return; }
    if (!canEdit()) session.edit = false;
    const requested = App.qs('asset');
    if (requested && !session.selection) { const n = session.graph.nodes.find(n => n.assetId === requested); if (n) session.selection = { kind: 'node', id: n.id }; }
    draw();
  }
  function draw() {
    const g = session.graph, editable = canEdit();
    root.innerHTML = `<div class="page-head"><div><h1>网络拓扑 <span class="badge primary">${esc(App.tenantName(currentId))}</span></h1><div class="sub">${esc(g.name)} · ${g.regions.length} 个站点 · ${g.nodes.length} 个节点 · ${g.edges.length} 条链路</div></div><div class="page-actions"><span id="nt-save-state" class="${session.dirty ? 'nt-unsaved' : 'muted small'}">${session.dirty ? '有未保存的修改' : g.revision ? '已保存 · v' + g.revision : '示例关系 · 尚未维护'}</span>${button('导出 JSON', 'export', 'download')}${editable ? session.edit ? button('取消修改', 'cancel', null) + button('保存拓扑', 'save', 'check', 'primary') : button('编辑拓扑', 'edit', 'edit', 'primary') : '<span class="nt-readonly">' + icon('lock') + ' 只读视图</span>'}</div></div>${session.edit ? '<div class="nt-message">编辑模式：拖动节点调整位置，或选中后使用方向键微调；设备与连线的修改在保存后生效。</div>' : ''}<div class="nt-layout"><section class="card"><div class="nt-toolbar"><div class="row"><input class="input" id="nt-search" aria-label="搜索拓扑节点" placeholder="搜索设备名称或 IP" value="${esc(search)}"><select class="select" id="nt-site" aria-label="突出显示站点">${options([['all', '全部站点'], ...g.regions.map(r => [r.name, r.name])], site)}</select></div><div class="row">${session.edit ? button('节点', 'add-node', 'plus', 'sm') + button('链路', 'add-edge', 'link', 'sm') + button('名称', 'rename', 'edit', 'sm') : '<span class="xs muted">点击节点或链路查看详情</span>'}</div></div><div class="nt-canvas ${session.edit ? 'nt-editing' : ''}" id="nt-canvas" aria-label="客户网络拓扑画布"><div id="nt-scene" class="nt-scene"></div><div class="nt-controls"><button type="button" class="icon-btn" data-nt="zoom-out" aria-label="缩小">${icon('minus')}</button><output id="nt-zoom">100%</output><button type="button" class="icon-btn" data-nt="zoom-in" aria-label="放大">${icon('plus')}</button><button type="button" class="icon-btn" data-nt="fit" aria-label="适应画布" title="适应画布">${icon('monitor')}</button></div><div class="nt-canvas-help">拖动空白平移 · Ctrl + 滚轮缩放${session.edit ? ' · 方向键移动选中节点' : ''}</div></div><div class="nt-legend">${Object.entries(M.statuses).map(([k,v]) => `<span><i style="--lc:${({ok:'#16a34a',warning:'#d97706',down:'#dc2626',unknown:'#94a3b8',maintenance:'#7c3aed'})[k]}"></i>${v}</span>`).join('')}<span style="margin-left:auto">虚线表示链路未知或中断</span></div></section><aside class="card nt-detail" id="nt-detail"></aside></div><div class="nt-meta">${g.updatedAt ? `最近保存：${esc(App.fmt.dt(g.updatedAt))} · ${esc(g.updatedBy)} · v${g.revision}` : '初始拓扑为人工维护示例，实际网络连接待核实。'} · 关联节点读取现有资产状态；未绑定的链路监测数据显示“—”。</div>`;
    canvas = root.querySelector('#nt-canvas'); scene = root.querySelector('#nt-scene');
    paint(); detail(); fit(); bindCanvas();
    observer = new ResizeObserver(() => { if (canvas.isConnected && !drag) fit(); }); observer.observe(canvas);
    root.onclick = handleClick;
    root.querySelector('#nt-search').oninput = e => { search = e.target.value.trim(); paint(); detail(); };
    root.querySelector('#nt-site').onchange = e => { site = e.target.value; paint(); detail(); };
  }
  function compact(n) { return session.graph.nodes.some(other => other.id !== n.id && other.site === n.site && Math.abs(other.y - n.y) < 10 && Math.abs(other.x - n.x) < 180); }
  function nodeWidth(n) { return compact(n) ? 124 : 168; }
  function match(n) { return (site === 'all' || n.site === site) && (!search || `${n.name} ${n.ip} ${n.site}`.toLowerCase().includes(search.toLowerCase())); }
  function selected(kind,id) { return session.selection?.kind === kind && session.selection?.id === id; }
  function edgeMarkup(e) {
    const a = session.graph.nodes.find(n => n.id === e.source), b = session.graph.nodes.find(n => n.id === e.target); if (!a || !b) return '';
    const x1 = a.x + nodeWidth(a) / 2, y1 = a.y + 77, x2 = b.x + nodeWidth(b) / 2, y2 = b.y;
    const midpoint = (y1 + y2) / 2, path = `M${x1},${y1} C${x1},${midpoint} ${x2},${midpoint} ${x2},${y2}`;
    return `<g class="nt-edge ${e.status} ${selected('edge',e.id) ? 'selected' : ''} ${match(a) || match(b) ? '' : 'dimmed'}" role="button" tabindex="0" aria-label="链路 ${esc(e.name)} ${esc(a.name)} 至 ${esc(b.name)}" data-edge="${esc(e.id)}"><path class="nt-edge-hit" d="${path}"/><path class="nt-edge-visible" d="${path}"/><text class="nt-edge-label" x="${(x1+x2)/2+7}" y="${midpoint-6}">${esc(e.bandwidth >= 1000 ? e.bandwidth/1000 + ' Gbps' : e.bandwidth + ' Mbps')}</text></g>`;
  }
  function paint() {
    const g = session.graph;
    g.width = Math.max(650, ...g.regions.map(r => r.x+r.width+20), ...g.nodes.map(n=>n.x+190));
    g.height = Math.max(650, ...g.nodes.map(n=>n.y+115));
    scene.style.width = g.width+'px'; scene.style.height = g.height+'px';
    scene.innerHTML = g.regions.map(r => `<div class="nt-zone" style="left:${r.x}px;top:${r.y}px;width:${r.width}px;height:${r.height}px;opacity:${site==='all'||site===r.name?1:.25}"><div class="nt-zone-title">${icon('map-pin')}${esc(r.name)}</div></div>`).join('') + `<svg class="nt-edges" width="${g.width}" height="${g.height}" aria-label="网络连接关系">${g.edges.map(edgeMarkup).join('')}</svg>` + g.nodes.map(n => `<div role="button" tabindex="0" class="nt-node ${nodeStatus(n)} ${compact(n)?'compact':''} ${selected('node',n.id)?'selected':''} ${match(n)?'':'dimmed'}" data-node="${esc(n.id)}" aria-label="设备 ${esc(n.name)}，${M.statuses[nodeStatus(n)]}" title="${esc(n.name)} · ${M.statuses[nodeStatus(n)]}" style="left:${n.x}px;top:${n.y}px"><div class="nt-node-symbol">${icon(iconMap[n.kind])}</div><div style="min-width:0"><div class="nt-node-name">${esc(n.name)}</div><div class="nt-node-ip">${esc(n.ip||M.kinds[n.kind])}</div></div><span class="nt-node-state ${nodeStatus(n)}"></span></div>`).join('') + (!g.nodes.length?'<div class="nt-empty"><b>这张拓扑还没有节点</b><span>进入编辑模式，添加设备节点并建立连接。</span></div>':'');
    transform();
  }
  function transform() { scene.style.transform = `translate(${view.x}px,${view.y}px) scale(${view.scale})`; root.querySelector('#nt-zoom').textContent = Math.round(view.scale*100)+'%'; }
  function fit() { if(!canvas) return; view.scale=Math.min(1.2,(canvas.clientWidth-32)/session.graph.width,(canvas.clientHeight-55)/session.graph.height); view.x=(canvas.clientWidth-session.graph.width*view.scale)/2; view.y=15; transform(); }
  function zoom(factor) { const old=view.scale; view.scale=App.clamp(old*factor,.25,2); const x=canvas.clientWidth/2,y=canvas.clientHeight/2;view.x=x-(x-view.x)*view.scale/old;view.y=y-(y-view.y)*view.scale/old;transform(); }
  function dirty() { session.dirty=true;const el=root.querySelector('#nt-save-state');el.textContent='有未保存的修改';el.className='nt-unsaved'; }
  function selectObject(kind,id) {session.selection={kind,id};paint();detail();}
  function detail() {
    const box=root.querySelector('#nt-detail'),g=session.graph,selection=session.selection;
    let obj=selection?.kind==='node'?g.nodes.find(n=>n.id===selection.id):selection?.kind==='edge'?g.edges.find(e=>e.id===selection.id):null;
    if(!obj){const matches=g.nodes.filter(match);box.innerHTML=`<div class="card-head"><h3>${icon('network')} 拓扑概况</h3></div><div class="card-body"><div class="nt-summary"><span class="small muted">节点<strong>${g.nodes.length}</strong></span><span class="small muted">链路<strong>${g.edges.length}</strong></span><span class="small muted">异常节点<strong>${g.nodes.filter(n=>['warning','down'].includes(nodeStatus(n))).length}</strong></span></div><p class="small muted">选择设备查看监控状态；选择连线查看两端端口、带宽和链路信息。</p><div class="row between mt"><b class="small">节点目录</b><span class="xs muted">${matches.length} 个匹配</span></div><div class="nt-list">${matches.map(n=>`<button type="button" data-nt="select-node" data-id="${esc(n.id)}">${icon(iconMap[n.kind])}<span class="grow ellipsis">${esc(n.name)}</span></button>`).join('')||'<p class="small muted">没有匹配的节点</p>'}</div></div>`;return;}
    const node=selection.kind==='node';
    const asset=node?getAsset(obj):null;
    const rows=node?[['设备类型',M.kinds[obj.kind]],['所属站点',obj.site],['管理地址',obj.ip||'—'],['设备状态',M.statuses[nodeStatus(obj)]],['监控来源',asset?'关联资产':'手工维护'],['资产负责人',asset?.owner||'—'],['CPU / 内存',asset?`${asset.cpu??'—'}% / ${asset.mem??'—'}%`:'—'],['最近采集',asset?App.fmt.ago(asset.lastData):'未绑定监控'],['关联链路',g.edges.filter(e=>e.source===obj.id||e.target===obj.id).length+' 条']]:[['链路状态',M.statuses[obj.status]],['链路类型',obj.type],['源节点',g.nodes.find(n=>n.id===obj.source)?.name],['源端口',obj.sourcePort||'—'],['目标节点',g.nodes.find(n=>n.id===obj.target)?.name],['目标端口',obj.targetPort||'—'],['配置带宽',obj.bandwidth+' Mbps'],['VLAN',obj.vlan||'—'],['入站 / 出站',obj.inMbps==null?'—':`${obj.inMbps} / ${obj.outMbps} Mbps`],['延迟 / 丢包',obj.latency==null?'—':`${obj.latency} ms / ${obj.loss}%`],['监测绑定',obj.latency==null?'尚未绑定':'演示采集']];
    box.innerHTML=`<div class="card-head"><h3>${node?'设备':'链路'}详情</h3>${button('','unselect','x','ghost xs','aria-label="关闭详情"')}</div><div class="card-body"><div class="nt-object-icon">${icon(node?iconMap[obj.kind]:'link')}</div><h3>${esc(obj.name)}</h3><div class="mt8">${App.badge(({ok:'ok',warning:'warn',down:'danger',unknown:'unknown',maintenance:'maint'})[node?nodeStatus(obj):obj.status],M.statuses[node?nodeStatus(obj):obj.status],true)}</div><dl class="kv mt">${rows.map(([k,v])=>`<dt>${k}</dt><dd>${esc(v??'—')}</dd>`).join('')}</dl>${obj.note?`<p class="small muted mt">${esc(obj.note)}</p>`:''}<div class="nt-detail-actions">${node&&asset?`<a class="btn sm" href="asset-detail.html?id=${encodeURIComponent(asset.id)}&tenant=${encodeURIComponent(currentId)}&role=${App.state.role}">${icon('activity')} 监控详情</a>`:''}${session.edit?button('编辑',node?'edit-node':'edit-edge','edit','sm')+button('删除',node?'delete-node':'delete-edge','trash','sm danger'):''}</div></div>`;
  }
  function bindCanvas(){
    canvas.onpointerdown=e=>{if(e.target.closest('.nt-controls')||e.button!==0)return;const nodeEl=e.target.closest('[data-node]'),edgeEl=e.target.closest('[data-edge]');if(edgeEl){selectObject('edge',edgeEl.dataset.edge);return;}if(nodeEl){const n=session.graph.nodes.find(n=>n.id===nodeEl.dataset.node);selectObject('node',n.id);if(!session.edit||!canEdit())return;drag={kind:'node',id:n.id,x:e.clientX,y:e.clientY,originX:n.x,originY:n.y,moved:false};}else drag={kind:'pan',x:e.clientX,y:e.clientY,originX:view.x,originY:view.y,moved:false};canvas.setPointerCapture(e.pointerId);};
    canvas.onpointermove=e=>{if(!drag)return;let dx=e.clientX-drag.x,dy=e.clientY-drag.y;if(Math.abs(dx)+Math.abs(dy)<3)return;drag.moved=true;if(drag.kind==='pan'){view.x=drag.originX+dx;view.y=drag.originY+dy;transform();}else{const n=session.graph.nodes.find(n=>n.id===drag.id);n.x=Math.max(0,Math.round(drag.originX+dx/view.scale));n.y=Math.max(0,Math.round(drag.originY+dy/view.scale));dirty();paint();}};
    canvas.onpointerup=()=>{drag=null;};canvas.onpointercancel=()=>{drag=null;};
    canvas.onwheel=e=>{if(e.ctrlKey){e.preventDefault();zoom(e.deltaY<0?1.12:1/1.12);}};
    canvas.onkeydown=e=>{const n=e.target.closest('[data-node]'),edge=e.target.closest('[data-edge]');if(e.key==='Enter'||e.key===' '){if(n||edge){e.preventDefault();selectObject(n?'node':'edge',n?n.dataset.node:edge.dataset.edge);}}if(n&&session.edit&&canEdit()&&['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(e.key)){e.preventDefault();const obj=session.graph.nodes.find(x=>x.id===n.dataset.node),d=e.shiftKey?20:10;obj.x=Math.max(0,obj.x+(e.key==='ArrowLeft'?-d:e.key==='ArrowRight'?d:0));obj.y=Math.max(0,obj.y+(e.key==='ArrowUp'?-d:e.key==='ArrowDown'?d:0));dirty();paint();scene.querySelector(`[data-node="${obj.id}"]`).focus();}};
  }
  function editForm(title,html,onSubmit){const openedFor=currentId;const modal=App.modal({title,body:`<form id="nt-form"><div class="form-grid">${html}<div class="nt-form-error" role="alert"></div></div></form>`,foot:'<button type="button" class="btn" data-close>取消</button><button class="btn primary" type="submit" form="nt-form">应用修改</button>',onOpen(el){el.setAttribute('role','dialog');el.setAttribute('aria-label',title);el.querySelector('input,select,textarea')?.focus();el.querySelector('form').onsubmit=e=>{e.preventDefault();const error=el.querySelector('.nt-form-error');try{if(!canEdit()||!session.edit||currentId!==openedFor)throw new Error('当前客户或权限已变化，请重新打开编辑');const form=Object.fromEntries(new FormData(e.target));onSubmit(form);dirty();modal.close();draw();}catch(err){error.textContent=err.message;}};}});return modal;}
  function editNode(id){
    const g=session.graph,n=g.nodes.find(x=>x.id===id),available=DATA.assets.filter(a=>a.tenant===currentId&&!g.nodes.some(x=>x.assetId===a.id&&x.id!==id));
    editForm(n?'编辑设备节点':'添加设备节点',choice('关联监控资产','assetId',[['','手工节点（未接入监控）'],...available.map(a=>[a.id,a.name+' · '+a.site])],n?.assetId||'')+field('节点名称','name',n?.name||'','text','maxlength="60"')+choice('节点类型','kind',Object.entries(M.kinds),n?.kind||'router')+choice('所属站点','site',[...App.tenant().sites.map(s=>[s,s]),['外部网络','外部网络']],n?.site||App.tenant().sites[0])+field('管理地址','ip',n?.ip||'','text','maxlength="120"')+choice('手工状态（关联资产后以监控为准）','status',Object.entries(M.statuses),n?.status||'unknown')+`<div class="field full"><label for="nt-note">备注</label><textarea id="nt-note" class="input" name="note" style="height:72px" maxlength="500">${esc(n?.note||'')}</textarea><span class="hint">选择已有资产时自动使用资产名称、类型、地址和站点；仅添加到拓扑，不创建或删除真实设备。</span></div>`,f=>{const a=f.assetId?available.find(a=>a.id===f.assetId):null;if(f.assetId&&!a)throw new Error('所选资产不在当前客户范围');if(!a&&!f.name.trim())throw new Error('请填写节点名称');const candidate=M.clone(g),node={id:n?.id||'node-'+App.uid(),...f,name:a?a.name:f.name.trim(),ip:a?a.ip:f.ip.trim(),site:a?a.site:f.site,kind:a?M.assetKind(a):f.kind,x:n?.x??60,y:n?.y??Math.min(560,g.height-95)};if(n)candidate.nodes=candidate.nodes.map(x=>x.id===n.id?node:x);else candidate.nodes.push(node);M.validate(candidate,currentId,DATA.assets);session.graph=candidate;session.selection={kind:'node',id:node.id};});
  }
  function editEdge(id){const g=session.graph,e=g.edges.find(x=>x.id===id);if(g.nodes.length<2){App.toast('至少需要两个节点才能添加链路','warn');return;}const nodes=g.nodes.map(n=>[n.id,n.name]);editForm(e?'编辑网络链路':'添加网络链路',field('链路名称','name',e?.name||'','text','required maxlength="60"')+choice('链路类型','type',['以太网','专线','VPN','无线','其他'].map(t=>[t,t]),e?.type||'以太网')+choice('源节点','source',nodes,e?.source||nodes[0][0])+choice('目标节点','target',nodes,e?.target||nodes[1][0])+field('源端口','sourcePort',e?.sourcePort||'','text','maxlength="60"')+field('目标端口','targetPort',e?.targetPort||'','text','maxlength="60"')+field('配置带宽（Mbps）','bandwidth',e?.bandwidth||1000,'number','required min="1" max="1000000"')+field('VLAN（可留空）','vlan',e?.vlan||'','number','min="1" max="4094"')+choice('链路状态（人工维护）','status',Object.entries(M.statuses),e?.status||'unknown')+field('备注','note',e?.note||'','text','maxlength="500"'),f=>{const candidate=M.clone(g),edge={...(e||{}),...f,id:e?.id||'edge-'+App.uid(),name:f.name.trim(),bandwidth:Number(f.bandwidth),latency:e?.latency??null,loss:e?.loss??null,inMbps:e?.inMbps??null,outMbps:e?.outMbps??null};if(e)candidate.edges=candidate.edges.map(x=>x.id===e.id?edge:x);else candidate.edges.push(edge);M.validate(candidate,currentId,DATA.assets);session.graph=candidate;session.selection={kind:'edge',id:edge.id};});}
  async function handleClick(event){
    const el=event.target.closest('[data-nt]');if(!el)return;const action=el.dataset.nt;
    if(action==='unselect'){session.selection=null;paint();detail();return;}if(action==='select-node'){selectObject('node',el.dataset.id);return;}if(action==='fit'){fit();return;}if(action==='zoom-in'||action==='zoom-out'){zoom(action==='zoom-in'?1.2:1/1.2);return;}
    if(action==='export'){const blob=new Blob([JSON.stringify(session.graph,null,2)],{type:'application/json'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=session.graph.name+(session.dirty?'-草稿':'')+'.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);App.toast('已导出当前客户拓扑','ok');return;}
    if(!canEdit()){App.toast('当前角色没有拓扑维护权限','warn');return;}
    if(action==='edit'){session.edit=true;draw();return;}
    if(!session.edit)return;
    if(action==='cancel'){if(session.dirty&&!await App.confirm({title:'放弃未保存的修改？',text:'将恢复该客户最近保存的拓扑；其他客户的拓扑不受影响。',okText:'放弃修改',danger:true}))return;session.graph=M.clone(session.baseline);session.dirty=false;session.edit=false;session.selection=null;draw();return;}
    if(action==='save'){try{const saved=M.save(localStorage,session.graph,currentId,DATA.assets,App.USERS[App.state.role].name);session.graph=saved;session.baseline=M.clone(saved);session.dirty=false;session.edit=false;try{audit('network_topology.save',`保存网络拓扑 v${saved.revision}：${saved.nodes.length} 个节点、${saved.edges.length} 条链路`);}catch(err){App.toast('拓扑已保存，审计记录写入失败','warn');}draw();App.toast('已保存「'+esc(App.tenantName(currentId))+'」的网络拓扑','ok');}catch(err){App.toast(esc(err.message),'danger');}return;}
    if(action==='rename'){editForm('修改拓扑名称',field('拓扑名称','name',session.graph.name,'text','required maxlength="80"'),f=>{if(!f.name.trim())throw new Error('请填写拓扑名称');session.graph.name=f.name.trim();});return;}
    if(action==='add-node'||action==='edit-node'){editNode(action==='edit-node'?session.selection?.id:null);return;}if(action==='add-edge'||action==='edit-edge'){editEdge(action==='edit-edge'?session.selection?.id:null);return;}
    if(action==='delete-node'||action==='delete-edge'){const node=action==='delete-node',id=session.selection?.id;const obj=(node?session.graph.nodes:session.graph.edges).find(x=>x.id===id);if(!obj)return;const captured=currentId;if(!await App.confirm({title:node?'删除拓扑节点？':'删除网络链路？',text:`${esc(obj.name)}${node?'将从拓扑中移除，同时移除 '+session.graph.edges.filter(e=>e.source===id||e.target===id).length+' 条关联连线；监控资产保持不变。':'将从当前拓扑移除。'}`,okText:'删除',danger:true}))return;if(captured!==currentId||!canEdit()||!session.edit)return;if(node)M.removeNode(session.graph,id);else session.graph.edges=session.graph.edges.filter(e=>e.id!==id);session.selection=null;dirty();draw();}
  }
  window.addEventListener('beforeunload',event=>{if([...sessions.values()].some(s=>s.dirty)){event.preventDefault();event.returnValue='';}});
  // Capture context switches before the shared shell replaces the page.
  document.addEventListener('click',async event=>{const choice=event.target.closest('#tenantPop [data-t]');if(!choice||!session?.dirty||choice.dataset.t===currentId)return;event.preventDefault();event.stopImmediatePropagation();const next=choice.dataset.t;if(await App.confirm({title:'切换客户前保存拓扑？',text:'当前客户有未保存的修改。继续切换会保留本页草稿，返回该客户后仍可保存；关闭页面前请保存。',okText:'保留草稿并切换'}))App.setTenant(next);},true);
  App.page('network-topology',render);
})();
