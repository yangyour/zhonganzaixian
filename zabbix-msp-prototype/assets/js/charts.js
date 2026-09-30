/* =========================================================
   charts.js · 轻量 SVG 图表（折线 / 面积 / 柱状 / 环形 / 仪表 / 迷你图）
   无外部依赖；支持悬浮提示、阈值线、事件标记、图例开关、自适应宽度
   ========================================================= */
(function (global) {
  'use strict';
  const NS = 'http://www.w3.org/2000/svg';
  const PALETTE = ['#2563eb', '#16a34a', '#f59e0b', '#8b5cf6', '#06b6d4', '#ec4899', '#64748b', '#ef4444', '#84cc16', '#0ea5e9'];
  const pad2 = (n) => String(n).padStart(2, '0');
  const esc = (v) => String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  function niceTicks(min, max, n = 5) {
    if (!isFinite(min) || !isFinite(max)) { min = 0; max = 1; }
    if (max === min) { max = min + (min === 0 ? 1 : Math.abs(min) * 0.2); }
    const range = max - min; const rough = range / Math.max(1, n - 1);
    const mag = Math.pow(10, Math.floor(Math.log10(rough))); const norm = rough / mag;
    let step = norm < 1.5 ? 1 : norm < 3 ? 2 : norm < 7 ? 5 : 10; step *= mag;
    const lo = Math.floor(min / step) * step, hi = Math.ceil(max / step) * step;
    const ticks = []; for (let v = lo; v <= hi + step * 0.5; v += step) ticks.push(+v.toFixed(10));
    return { ticks, lo, hi };
  }
  const defFmt = (v) => { if (v == null) return '—'; const a = Math.abs(v); if (a >= 1e9) return (v / 1e9).toFixed(1) + 'G'; if (a >= 1e6) return (v / 1e6).toFixed(1) + 'M'; if (a >= 1e4) return (v / 1e3).toFixed(1) + 'k'; if (a >= 100) return v.toFixed(0); if (a >= 10) return v.toFixed(1); return (+v.toFixed(2)).toString(); };
  const timeFmt = (t, span) => { const d = new Date(t); if (span > 3 * 86400e3) return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:00`; return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`; };
  const timeFmtFull = (t) => { const d = new Date(t); return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`; };

  function svgEl(w, h) { const s = document.createElementNS(NS, 'svg'); s.setAttribute('viewBox', `0 0 ${w} ${h}`); s.setAttribute('width', w); s.setAttribute('height', h); s.style.width = '100%'; s.style.height = h + 'px'; return s; }
  function mount(el, height, render) {
    el.classList.add('chart'); el.style.height = height + 'px';
    let lastW = 0;
    const doRender = () => { const w = el.clientWidth; if (!w || w === lastW) return; lastW = w; el.innerHTML = ''; render(w); };
    doRender();
    if (global.ResizeObserver) { const ro = new ResizeObserver(() => doRender()); ro.observe(el); el._ro = ro; }
    else window.addEventListener('resize', doRender);
    el._rerender = () => { lastW = 0; doRender(); };
    if (!el.clientWidth) { const iv = setInterval(() => { if (el.clientWidth) { clearInterval(iv); doRender(); } }, 200); setTimeout(() => clearInterval(iv), 15000); }
  }
  function tip(el) { let t = el.querySelector('.tip'); if (!t) { t = document.createElement('div'); t.className = 'tip'; el.appendChild(t); } return t; }
  function place(t, el, x, y) { const w = el.clientWidth; t.style.display = 'block'; const tw = t.offsetWidth; t.style.left = (x + 14 + tw > w ? x - tw - 12 : x + 14) + 'px'; t.style.top = Math.max(0, y - 10) + 'px'; }

  /* ---------------- 折线 / 面积 ---------------- */
  function line(el, o) {
    const height = o.height || 220;
    mount(el, height, (W) => {
      const series = (o.series || []).map((s, i) => Object.assign({ color: PALETTE[i % PALETTE.length], visible: s.visible !== false }, s));
      const n = Math.max(...series.map(s => s.data.length), 0);
      const x = o.x || (series[0] && series[0].x) || Array.from({ length: n }, (_, i) => i);
      const legendH = o.legend === false || series.length < 2 ? 0 : 22;
      const m = { l: o.marginLeft || 46, r: o.marginRight || (o.thresholds && o.thresholds.length ? 54 : 14), t: 10 + legendH, b: 24 };
      const pw = W - m.l - m.r, ph = height - m.t - m.b;
      const vis = series.filter(s => s.visible);
      let vals = []; if (o.stacked) { for (let i = 0; i < n; i++) { let acc = 0; vis.forEach(s => { acc += (s.data[i] ?? 0); }); vals.push(acc); } } else vis.forEach(s => s.data.forEach(v => v != null && vals.push(v)));
      (o.thresholds || []).forEach(t => vals.push(t.value));
      let yMin = o.yMin != null ? o.yMin : Math.min(...vals, o.zero === false ? Infinity : 0); let yMax = o.yMax != null ? o.yMax : Math.max(...vals);
      if (!isFinite(yMin)) yMin = 0; if (!isFinite(yMax)) yMax = 1; if (yMax === yMin) yMax = yMin + 1;
      const nt = niceTicks(yMin, yMax, 5); if (o.yMax == null) yMax = nt.hi; if (o.yMin == null) yMin = nt.lo;
      const X = (i) => m.l + (n <= 1 ? pw / 2 : (i / (n - 1)) * pw), Y = (v) => m.t + ph - ((v - yMin) / (yMax - yMin)) * ph;
      const yf = o.yFormat || defFmt;
      const svg = svgEl(W, height); const gid = 'g' + Math.random().toString(36).slice(2, 7);
      let defs = '<defs>' + series.map((s, i) => `<linearGradient id="${gid}${i}" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="${s.color}" stop-opacity="${o.stacked ? .55 : .28}"/><stop offset="1" stop-color="${s.color}" stop-opacity="${o.stacked ? .25 : .02}"/></linearGradient>`).join('') + `<clipPath id="${gid}c"><rect x="${m.l}" y="${m.t - 2}" width="${pw}" height="${ph + 4}"/></clipPath></defs>`;
      let g = defs;
      // grid + y labels
      nt.ticks.forEach(v => { if (v < yMin - 1e-9 || v > yMax + 1e-9) return; const y = Y(v); g += `<line x1="${m.l}" x2="${m.l + pw}" y1="${y}" y2="${y}" stroke="#eef1f5" stroke-dasharray="${v === yMin ? '' : '3 3'}"/><text x="${m.l - 8}" y="${y + 4}" text-anchor="end" font-size="10.5" fill="#94a3b8">${esc(yf(v))}</text>`; });
      // x labels
      const span = (x[n - 1] - x[0]) || 1; const isTime = x[0] > 1e11; const xl = Math.min(n, Math.max(2, Math.floor(pw / 90)));
      for (let k = 0; k < xl; k++) { const i = Math.round((k / (xl - 1)) * (n - 1)); const lab = o.xFormat ? o.xFormat(x[i], i) : (isTime ? timeFmt(x[i], span) : x[i]); g += `<text x="${X(i)}" y="${height - 8}" text-anchor="${k === 0 ? 'start' : k === xl - 1 ? 'end' : 'middle'}" font-size="10.5" fill="#94a3b8">${esc(lab)}</text>`; }
      // series
      const stackBase = new Array(n).fill(0);
      const stackTops = [];
      vis.forEach((s, si) => {
        const pts = []; const tops = [];
        for (let i = 0; i < n; i++) { const v = s.data[i]; if (v == null) { pts.push(null); tops.push(null); continue; } const top = o.stacked ? stackBase[i] + v : v; tops.push(top); pts.push([X(i), Y(top), o.stacked ? Y(stackBase[i]) : Y(Math.max(yMin, 0))]); if (o.stacked) stackBase[i] += v; }
        stackTops.push(tops);
        let d = '', area = '', open = false, segStart = null;
        const seg = [];
        pts.forEach((p, i) => { if (!p) { if (seg.length) { d += pathFrom(seg, o.smooth); area += areaFrom(seg, o.smooth); seg.length = 0; } return; } seg.push(p); });
        if (seg.length) { d += pathFrom(seg, o.smooth); area += areaFrom(seg, o.smooth); }
        if (s.area || o.area || o.stacked) g += `<path d="${area}" fill="url(#${gid}${series.indexOf(s)})" clip-path="url(#${gid}c)"/>`;
        g += `<path d="${d}" fill="none" stroke="${s.color}" stroke-width="${s.width || 1.8}" stroke-linejoin="round" stroke-linecap="round" ${s.dash ? `stroke-dasharray="${s.dash}"` : ''} clip-path="url(#${gid}c)"/>`;
        if (n <= 40 && o.dots !== false) pts.forEach(p => p && (g += `<circle cx="${p[0]}" cy="${p[1]}" r="2.2" fill="#fff" stroke="${s.color}" stroke-width="1.5"/>`));
      });
      // thresholds
      (o.thresholds || []).forEach(t => { const y = Y(t.value); if (y < m.t || y > m.t + ph) return; g += `<line x1="${m.l}" x2="${m.l + pw}" y1="${y}" y2="${y}" stroke="${t.color || '#dc2626'}" stroke-dasharray="5 4" stroke-width="1.2"/><text x="${m.l + pw + 6}" y="${y + 4}" font-size="10.5" fill="${t.color || '#dc2626'}" font-weight="600">${esc(t.label || yf(t.value))}</text>`; });
      // markers (events)
      (o.markers || []).forEach(mk => { let i = mk.index; if (i == null && mk.t != null) { i = 0; let best = Infinity; x.forEach((xv, k) => { const d = Math.abs(xv - mk.t); if (d < best) { best = d; i = k; } }); } if (i == null) return; const xx = X(i); const rightSide = xx > m.l + pw * 0.75; g += `<line x1="${xx}" x2="${xx}" y1="${m.t}" y2="${m.t + ph}" stroke="${mk.color || '#dc2626'}" stroke-width="1.2" stroke-dasharray="2 3"/><path d="M${xx - 5} ${m.t - 6} L${xx + 5} ${m.t - 6} L${xx} ${m.t + 1} Z" fill="${mk.color || '#dc2626'}"/>${mk.label ? `<text x="${rightSide ? xx - 6 : xx + 6}" y="${m.t + 10}" text-anchor="${rightSide ? 'end' : 'start'}" font-size="10" fill="${mk.color || '#dc2626'}" font-weight="600">${esc(mk.label)}</text>` : ''}`; });
      // legend
      if (legendH) g += series.map((s, i) => `<g class="lg" data-i="${i}" style="cursor:pointer" opacity="${s.visible ? 1 : .35}"><rect x="${m.l + i * 110}" y="4" width="10" height="3" rx="1.5" fill="${s.color}"/><text x="${m.l + i * 110 + 15}" y="9" font-size="11" fill="#64748b">${esc(s.name || 'series ' + (i + 1))}</text></g>`).join('');
      // hover layer
      g += `<line class="cross" x1="0" x2="0" y1="${m.t}" y2="${m.t + ph}" stroke="#94a3b8" stroke-width="1" style="display:none"/><g class="hp"></g><rect class="hit" x="${m.l}" y="${m.t}" width="${Math.max(0, pw)}" height="${Math.max(0, ph)}" fill="transparent"/>`;
      svg.innerHTML = g; el.appendChild(svg);
      const tp = tip(el); const cross = svg.querySelector('.cross'); const hp = svg.querySelector('.hp'); const hit = svg.querySelector('.hit');
      hit.addEventListener('mousemove', (e) => {
        const r = svg.getBoundingClientRect(); const sx = W / r.width; const mx = (e.clientX - r.left) * sx; const my = (e.clientY - r.top) * sx;
        const i = Math.round(((mx - m.l) / pw) * (n - 1)); if (i < 0 || i >= n) return;
        const xx = X(i); cross.setAttribute('x1', xx); cross.setAttribute('x2', xx); cross.style.display = 'block';
        let dots = '', rows = '';
        vis.forEach((s, si) => { const v = s.data[i]; if (v == null) return; const top = o.stacked ? stackTops[si][i] : v; dots += `<circle cx="${xx}" cy="${Y(top)}" r="4" fill="${s.color}" stroke="#fff" stroke-width="2"/>`; rows += `<div><i style="background:${s.color}"></i>${esc(s.name || '')} <b>${esc(o.tipFormat ? o.tipFormat(v, s) : (s.format || yf)(v))}${s.unit || o.unit || ''}</b></div>`; });
        hp.innerHTML = dots; tp.innerHTML = `<div class="t">${esc(o.xTip ? o.xTip(x[i], i) : (isTime ? timeFmtFull(x[i]) : x[i]))}</div>${rows}`;
        place(tp, el, (e.clientX - r.left), (e.clientY - r.top));
      });
      hit.addEventListener('mouseleave', () => { cross.style.display = 'none'; hp.innerHTML = ''; tp.style.display = 'none'; });
      svg.querySelectorAll('.lg').forEach(lg => lg.addEventListener('click', () => { const s = series[+lg.dataset.i]; o.series[series.indexOf(s)].visible = !s.visible; el._rerender(); }));
    });
  }
  function pathFrom(seg, smooth) {
    if (!seg.length) return ''; if (seg.length === 1) return `M${seg[0][0]} ${seg[0][1]} L${seg[0][0] + 0.01} ${seg[0][1]}`;
    if (!smooth) return 'M' + seg.map(p => `${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(' L');
    let d = `M${seg[0][0]} ${seg[0][1]}`;
    for (let i = 0; i < seg.length - 1; i++) { const p0 = seg[i], p1 = seg[i + 1]; const cx = (p0[0] + p1[0]) / 2; d += ` C${cx} ${p0[1]} ${cx} ${p1[1]} ${p1[0]} ${p1[1]}`; }
    return d;
  }
  function areaFrom(seg, smooth) { if (!seg.length) return ''; const d = pathFrom(seg, smooth); const last = seg[seg.length - 1], first = seg[0]; return d + ` L${last[0]} ${last[2]} L${first[0]} ${first[2]} Z`; }

  /* ---------------- 柱状 ---------------- */
  function bar(el, o) {
    const height = o.height || 220;
    mount(el, height, (W) => {
      const cats = o.categories || []; const n = cats.length;
      const series = (o.series || []).map((s, i) => Object.assign({ color: PALETTE[i % PALETTE.length] }, s));
      const legendH = o.legend === false || series.length < 2 ? 0 : 22;
      const m = { l: o.marginLeft || 44, r: 14, t: 10 + legendH, b: 26 }; const pw = W - m.l - m.r, ph = height - m.t - m.b;
      let maxV = 0; for (let i = 0; i < n; i++) { if (o.stacked) { let a = 0; series.forEach(s => a += (s.data[i] || 0)); maxV = Math.max(maxV, a); } else series.forEach(s => maxV = Math.max(maxV, s.data[i] || 0)); }
      (o.thresholds || []).forEach(t => maxV = Math.max(maxV, t.value));
      if (o.yMax != null) maxV = o.yMax;
      const nt = niceTicks(0, maxV || 1, 5); const yMax = o.yMax != null ? o.yMax : nt.hi; const Y = (v) => m.t + ph - (v / yMax) * ph; const yf = o.yFormat || defFmt;
      const svg = svgEl(W, height); let g = '';
      nt.ticks.forEach(v => { if (v > yMax + 1e-9) return; const y = Y(v); g += `<line x1="${m.l}" x2="${m.l + pw}" y1="${y}" y2="${y}" stroke="#eef1f5" stroke-dasharray="${v === 0 ? '' : '3 3'}"/><text x="${m.l - 8}" y="${y + 4}" text-anchor="end" font-size="10.5" fill="#94a3b8">${esc(yf(v))}</text>`; });
      const gw = pw / Math.max(1, n); const inner = gw * (o.barRatio || 0.62); const bw = o.stacked ? inner : inner / series.length; const r = Math.min(3, bw / 2);
      const bars = [];
      for (let i = 0; i < n; i++) {
        let base = 0; const gx = m.l + i * gw + (gw - inner) / 2;
        series.forEach((s, si) => { const v = s.data[i] || 0; const x = o.stacked ? gx : gx + si * bw; const y0 = o.stacked ? Y(base) : Y(0); const y1 = o.stacked ? Y(base + v) : Y(v); const h = Math.max(0, y0 - y1); g += `<rect x="${x}" y="${y1}" width="${Math.max(1, bw - (o.stacked ? 0 : 2))}" height="${h}" fill="${s.colors ? s.colors[i] : s.color}" rx="${o.stacked ? 0 : r}" opacity=".92"/>`; if (o.showValues && !o.stacked && v) g += `<text x="${x + bw / 2}" y="${y1 - 4}" text-anchor="middle" font-size="10" fill="#64748b">${esc(yf(v))}</text>`; if (o.stacked) base += v; });
        const showEvery = Math.max(1, Math.ceil(n / Math.floor(pw / 56)));
        if (i % showEvery === 0 || i === n - 1) g += `<text x="${gx + inner / 2}" y="${height - 8}" text-anchor="middle" font-size="10.5" fill="#94a3b8">${esc(cats[i])}</text>`;
        bars.push({ x: m.l + i * gw, w: gw });
      }
      (o.thresholds || []).forEach(t => { const y = Y(t.value); g += `<line x1="${m.l}" x2="${m.l + pw}" y1="${y}" y2="${y}" stroke="${t.color || '#dc2626'}" stroke-dasharray="5 4" stroke-width="1.2"/><text x="${m.l + pw}" y="${y - 4}" text-anchor="end" font-size="10.5" fill="${t.color || '#dc2626'}" font-weight="600">${esc(t.label || '')}</text>`; });
      if (legendH) g += series.map((s, i) => `<rect x="${m.l + i * 110}" y="4" width="10" height="10" rx="2" fill="${s.color}"/><text x="${m.l + i * 110 + 15}" y="13" font-size="11" fill="#64748b">${esc(s.name)}</text>`).join('');
      g += `<rect class="hl" x="0" y="${m.t}" width="0" height="${ph}" fill="#0f172a" opacity=".04" style="display:none"/><rect class="hit" x="${m.l}" y="${m.t}" width="${Math.max(0, pw)}" height="${Math.max(0, ph)}" fill="transparent"/>`;
      svg.innerHTML = g; el.appendChild(svg);
      const tp = tip(el); const hl = svg.querySelector('.hl'); const hit = svg.querySelector('.hit');
      hit.addEventListener('mousemove', (e) => { const rc = svg.getBoundingClientRect(); const mx = (e.clientX - rc.left) * (W / rc.width); const i = Math.floor((mx - m.l) / gw); if (i < 0 || i >= n) return; hl.setAttribute('x', bars[i].x); hl.setAttribute('width', bars[i].w); hl.style.display = 'block'; tp.innerHTML = `<div class="t">${esc(cats[i])}</div>` + series.map(s => `<div><i style="background:${s.colors ? s.colors[i] : s.color}"></i>${esc(s.name || '')} <b>${esc((s.format || yf)(s.data[i] || 0))}${s.unit || o.unit || ''}</b></div>`).join(''); place(tp, el, e.clientX - rc.left, e.clientY - rc.top); });
      hit.addEventListener('mouseleave', () => { hl.style.display = 'none'; tp.style.display = 'none'; });
    });
  }

  /* ---------------- 环形 ---------------- */
  function donut(el, o) {
    const size = o.size || 150, th = o.thickness || 20; const data = (o.data || []).map((d, i) => Object.assign({ color: PALETTE[i % PALETTE.length] }, d));
    const total = data.reduce((a, d) => a + d.value, 0) || 1;
    el.classList.add('chart'); el.innerHTML = ''; el.style.height = '';
    const wrap = document.createElement('div'); wrap.style.cssText = 'display:flex;align-items:center;gap:18px;flex-wrap:wrap';
    const svg = svgEl(size, size); svg.style.width = size + 'px'; svg.style.flex = 'none';
    const cx = size / 2, cy = size / 2, r = size / 2 - 4, ri = r - th; let a0 = -Math.PI / 2; let g = '';
    data.forEach((d, i) => { const a1 = a0 + (d.value / total) * Math.PI * 2; g += `<path data-i="${i}" d="${arc(cx, cy, r, ri, a0, a1 - (data.length > 1 ? 0.02 : 0))}" fill="${d.color}" style="cursor:pointer;transition:opacity .15s"/>`; a0 = a1; });
    if (o.center) g += `<text x="${cx}" y="${cy - 2}" text-anchor="middle" font-size="${o.centerSize || 22}" font-weight="700" fill="#0f172a">${esc(o.center.value)}</text><text x="${cx}" y="${cy + 16}" text-anchor="middle" font-size="11" fill="#64748b">${esc(o.center.label || '')}</text>`;
    svg.innerHTML = g; wrap.appendChild(svg);
    if (o.legend !== false) { const lg = document.createElement('div'); lg.style.cssText = 'display:flex;flex-direction:column;gap:6px;font-size:12px;min-width:120px;flex:1'; lg.innerHTML = data.map((d, i) => `<div data-i="${i}" style="display:flex;align-items:center;gap:8px;cursor:pointer"><i style="width:10px;height:10px;border-radius:3px;background:${d.color};display:inline-block"></i><span style="color:var(--text-2);flex:1">${esc(d.name)}</span><b class="num" style="font-weight:600">${esc(o.format ? o.format(d.value) : d.value)}</b><span class="faint num" style="width:42px;text-align:right">${(d.value / total * 100).toFixed(0)}%</span></div>`).join(''); wrap.appendChild(lg); }
    el.appendChild(wrap);
    const tp = tip(el);
    const paths = svg.querySelectorAll('path[data-i]');
    wrap.addEventListener('mousemove', (e) => { const t = e.target.closest('[data-i]'); if (!t) { tp.style.display = 'none'; paths.forEach(p => p.style.opacity = 1); return; } const i = +t.dataset.i; paths.forEach(p => p.style.opacity = +p.dataset.i === i ? 1 : .45); const rc = el.getBoundingClientRect(); tp.innerHTML = `<div><i style="background:${data[i].color}"></i>${esc(data[i].name)} <b>${esc(o.format ? o.format(data[i].value) : data[i].value)}</b> · ${(data[i].value / total * 100).toFixed(1)}%</div>`; place(tp, el, e.clientX - rc.left, e.clientY - rc.top); });
    wrap.addEventListener('mouseleave', () => { tp.style.display = 'none'; paths.forEach(p => p.style.opacity = 1); });
    wrap.addEventListener('click', (e) => { const t = e.target.closest('[data-i]'); if (t && o.onClick) o.onClick(data[+t.dataset.i]); });
  }
  function arc(cx, cy, r, ri, a0, a1) {
    const large = a1 - a0 > Math.PI ? 1 : 0;
    const p = (rr, a) => [cx + rr * Math.cos(a), cy + rr * Math.sin(a)];
    const [x0, y0] = p(r, a0), [x1, y1] = p(r, a1), [x2, y2] = p(ri, a1), [x3, y3] = p(ri, a0);
    return `M${x0} ${y0} A${r} ${r} 0 ${large} 1 ${x1} ${y1} L${x2} ${y2} A${ri} ${ri} 0 ${large} 0 ${x3} ${y3} Z`;
  }

  /* ---------------- 仪表 ---------------- */
  function gauge(el, o) {
    const size = o.size || 150; const w = size, h = size * 0.62; const cx = w / 2, cy = h - 6; const r = w / 2 - 8; const th = o.thickness || 12;
    const min = o.min ?? 0, max = o.max ?? 100; const v = Math.max(min, Math.min(max, o.value ?? 0)); const pct = (v - min) / (max - min);
    const thr = o.thresholds || [{ to: 70, color: '#16a34a' }, { to: 85, color: '#f59e0b' }, { to: 100, color: '#dc2626' }];
    const color = o.color || (thr.find(t => v <= t.to) || thr[thr.length - 1]).color;
    const A = (p) => Math.PI + p * Math.PI; const P = (rr, a) => [cx + rr * Math.cos(a), cy + rr * Math.sin(a)];
    const seg = (p0, p1, col, op, rr, tt) => { const [x0, y0] = P(rr, A(p0)), [x1, y1] = P(rr, A(p1)); return `<path d="M${x0} ${y0} A${rr} ${rr} 0 0 1 ${x1} ${y1}" fill="none" stroke="${col}" stroke-width="${tt}" stroke-linecap="${o.round === false ? 'butt' : 'round'}" opacity="${op}"/>`; };
    let g = seg(0, 1, '#e8edf3', 1, r, th);
    // 阈值色带（细）
    let prev = 0; thr.forEach(t => { const p1 = Math.max(0, Math.min(1, (t.to - min) / (max - min))); g += seg(prev, p1, t.color, .35, r - th - 3, 3); prev = p1; });
    if (pct > 0) g += seg(0, Math.max(0.005, pct), color, 1, r, th);
    g += `<text x="${cx}" y="${cy - 10}" text-anchor="middle" font-size="${o.valueSize || 22}" font-weight="700" fill="#0f172a">${esc(o.format ? o.format(v) : (Math.round(v * 10) / 10))}<tspan font-size="11" font-weight="500" fill="#64748b">${esc(o.unit == null ? '%' : o.unit)}</tspan></text>`;
    g += `<text x="${P(r, A(0))[0] - 2}" y="${cy + 12}" font-size="9.5" fill="#94a3b8">${esc(min)}</text><text x="${P(r, A(1))[0] + 2}" y="${cy + 12}" text-anchor="end" font-size="9.5" fill="#94a3b8">${esc(max)}</text>`;
    el.innerHTML = `<div class="gauge-wrap"><svg viewBox="0 0 ${w} ${h}" width="${w}" height="${h}" style="display:block;overflow:visible">${g}</svg>${o.label ? `<div class="g-label">${esc(o.label)}</div>` : ''}${o.sub ? `<div class="xs faint">${esc(o.sub)}</div>` : ''}</div>`;
  }

  /* ---------------- 迷你图 ---------------- */
  function spark(el, data, o = {}) {
    const w = o.width || el.clientWidth || 80, h = o.height || el.clientHeight || 24; const color = o.color || '#2563eb';
    const vals = data.filter(v => v != null); if (!vals.length) { el.innerHTML = ''; return; }
    const min = o.min ?? Math.min(...vals), max = o.max ?? Math.max(...vals); const rng = max - min || 1;
    const X = (i) => (i / Math.max(1, data.length - 1)) * (w - 2) + 1, Y = (v) => h - 2 - ((v - min) / rng) * (h - 4);
    const pts = data.map((v, i) => v == null ? null : [X(i), Y(v)]).filter(Boolean);
    const d = 'M' + pts.map(p => `${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(' L');
    const area = d + ` L${pts[pts.length - 1][0]} ${h} L${pts[0][0]} ${h} Z`;
    const last = pts[pts.length - 1];
    const gid = 's' + Math.random().toString(36).slice(2, 7);
    el.innerHTML = `<svg viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" style="width:100%;height:100%;display:block;overflow:visible"><defs><linearGradient id="${gid}" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="${color}" stop-opacity=".3"/><stop offset="1" stop-color="${color}" stop-opacity="0"/></linearGradient></defs>${o.area ? `<path d="${area}" fill="url(#${gid})"/>` : ''}<path d="${d}" fill="none" stroke="${color}" stroke-width="${o.width2 || 1.5}" stroke-linejoin="round" vector-effect="non-scaling-stroke"/>${o.dot !== false ? `<circle cx="${last[0]}" cy="${last[1]}" r="2" fill="${color}"/>` : ''}</svg>`;
  }
  function sparkHtml(data, o = {}) { const id = 'sp' + Math.random().toString(36).slice(2, 8); setTimeout(() => { const el = document.getElementById(id); if (el) spark(el, data, o); }, 0); return `<span class="spark" id="${id}" style="${o.w ? `width:${o.w}px;` : ''}${o.h ? `height:${o.h}px` : ''}"></span>`; }

  /* ---------------- 横向排行条 ---------------- */
  function hbars(el, o) {
    const items = o.items || []; const max = o.max || Math.max(...items.map(i => i.value), 1);
    el.innerHTML = `<div class="stack" style="gap:8px">${items.map(it => { const p = Math.min(100, it.value / max * 100); const c = it.color || (p >= 90 ? 'var(--danger)' : p >= 75 ? 'var(--warn)' : 'var(--primary)'); return `<div class="meter-row" ${it.href ? `style="cursor:pointer" onclick="location.href='${it.href}'"` : ''}><span class="lbl" title="${esc(it.label)}" style="width:${o.labelWidth || 140}px">${esc(it.label)}</span><div class="meter grow"><i style="width:${p}%;background:${c}"></i></div><span class="val">${esc(it.text != null ? it.text : it.value)}</span></div>`; }).join('')}</div>`;
  }

  global.Charts = { line, bar, donut, gauge, spark, sparkHtml, hbars, PALETTE, niceTicks, defFmt };
})(window);
