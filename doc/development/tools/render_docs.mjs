import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const doc = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const project = path.resolve(doc, '../..');
const packages = process.argv[2];
if (!packages) throw new Error('Pass bundled Node package directory.');
const { marked } = await import(pathToFileURL(path.join(packages, 'marked/lib/marked.esm.js')).href);
const escape = s => s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
const files = ['README.md', ...fs.readdirSync(doc).filter(n => /^\d{2}_.*\.md$/.test(n)).sort()];
const names = ['阅读入口','开发顺序与任务','模块与页面交互','数据模型与接口','客户确认与全局设置','成熟组件复用','测试验收与交付','合同逐项追踪'];
const data = files.map((name, i) => ({name, id: `doc${i}`, title: names[i] || name, md: fs.readFileSync(path.join(doc, name), 'utf8')}));
const links = Object.fromEntries(data.map(d => [d.name, d.id]));
const cfg = JSON.parse(fs.readFileSync(path.join(doc, 'contracts/config-baseline.json'), 'utf8'));
const summary = JSON.parse(fs.readFileSync(path.join(doc, 'evidence/document_validation.json'), 'utf8'));
const nav = data.map((d, i) => `<button class="nav-item ${i ? '' : 'active'}" data-doc="${d.id}"><span class="num">${String(i).padStart(2,'0')}</span><span>${escape(d.title)}</span></button>`).join('');
const sections = data.map((d, i) => {
  let body = marked.parse(d.md, {gfm:true});
  const headings = [];
  body = body.replace(/<h([23])>([\s\S]*?)<\/h\1>/g, (_, level, text) => {
    const id = `${d.id}-s${headings.length}`;
    headings.push({id,text:text.replace(/<[^>]*>/g,''),level});
    return `<h${level} id="${id}">${text}</h${level}>`;
  });
  body = body.replace(/href="([^"]+)"/g, (all, target) => {
    const decoded = decodeURIComponent(target);
    if (links[decoded]) return `href="#${links[decoded]}" data-open-doc="${links[decoded]}"`;
    if (!/^(https?:|#|mailto:)/.test(decoded)) return `href="../../doc/development/${escape(target)}"`;
    return all;
  });
  body = body.replaceAll('<table>', '<div class="table-scroll"><table>').replaceAll('</table>', '</table></div>');
  const toc = headings.filter(h => h.level === '2').map(h=>`<a href="#${h.id}" data-section="${h.id}">${escape(h.text)}</a>`).join('');
  return `<article id="${d.id}" ${i ? 'hidden' : ''}><div class="article-meta">V0.1 · 2026年9月30日 · 开发设计</div><details class="toc"><summary>本篇目录</summary><div>${toc}</div></details>${body}<p class="source-link"><a href="../../doc/development/${encodeURIComponent(d.name)}">打开本篇可编辑源文件</a></p></article>`;
}).join('');
const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>MOP平台开发文档</title><style>
:root{color-scheme:light;--ink:#192c43;--muted:#65768d;--blue:#2465bf;--line:#dce4ee;--paper:#fff}*{box-sizing:border-box}body{margin:0;font:15px/1.8 "Microsoft YaHei","PingFang SC",system-ui,sans-serif;color:var(--ink);background:#f4f7fb}button,input{font:inherit}button{cursor:pointer}header{height:74px;background:#fff;border-bottom:1px solid var(--line);display:flex;align-items:center;justify-content:space-between;padding:0 30px;position:sticky;top:0;z-index:5}.brand{font-size:19px;font-weight:700}.brand small{font-size:12px;font-weight:400;color:var(--muted);display:block;line-height:1.3}.actions{display:flex;gap:10px}.actions button{padding:7px 14px;border:1px solid var(--line);border-radius:7px;background:white;color:var(--ink)}.mobile-toggle{display:none}.layout{display:grid;grid-template-columns:260px minmax(0,1fr);max-width:1600px;margin:auto}.sidebar{padding:26px 18px;position:sticky;top:74px;height:calc(100vh - 74px);overflow:auto;border-right:1px solid var(--line)}.sidebar label{font-size:12px;color:var(--muted);display:block;margin:0 10px 6px}.sidebar input{width:100%;border:1px solid #cdd8e6;border-radius:7px;padding:10px 12px;background:white;font-size:13px;margin-bottom:18px}.nav-item{display:flex;width:100%;gap:12px;padding:12px 10px;border:0;border-radius:7px;text-align:left;background:transparent;color:#4d607b;line-height:1.5;margin:3px 0}.nav-item:hover{background:#eaf0f8}.nav-item.active{background:#e4edfb;color:#1855a2;font-weight:700}.num{font-size:12px;opacity:.65;align-self:center}.side-note{font-size:12px;line-height:1.8;color:var(--muted);margin:26px 10px}.no-results{font-size:13px;color:var(--muted);margin:10px}.content{min-width:0;padding:30px 38px 70px}.stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:23px}.stat{background:#fff;border:1px solid var(--line);border-radius:9px;padding:13px 16px;line-height:1.5}.stat b{display:block;font-size:25px;font-variant-numeric:tabular-nums;color:#18477b}.stat span{font-size:12px;color:var(--muted)}article{background:var(--paper);border:1px solid var(--line);border-radius:11px;padding:32px 38px;box-shadow:0 4px 16px #18385f04;overflow-wrap:anywhere}article[hidden]{display:none}.article-meta{font-size:12px;color:var(--muted);margin-bottom:15px}h1{font-size:28px;line-height:1.45;margin:20px 0 20px;color:#112d4d}h2{font-size:21px;line-height:1.5;margin:37px 0 16px;scroll-margin-top:96px}h3{font-size:17px;line-height:1.6;margin:29px 0 12px;scroll-margin-top:96px}p{margin:14px 0}a{color:var(--blue);text-underline-offset:3px}li{margin:7px 0}ul,ol{padding-left:25px}.toc{border:1px solid #dbe5f1;background:#f7faff;border-radius:7px;padding:10px 15px;font-size:13px;color:#45617e}.toc summary{cursor:pointer}.toc div{display:flex;flex-wrap:wrap;gap:7px 20px;margin-top:12px}.table-scroll{overflow-x:auto;border:1px solid var(--line);border-radius:7px;margin:20px 0}table{border-collapse:collapse;width:100%;font-size:13px;min-width:650px;line-height:1.75}th,td{padding:11px 13px;text-align:left;vertical-align:top;border-bottom:1px solid #e4eaf1;border-right:1px solid #e9edf3}th{background:#eef3f9;color:#2e496a;font-weight:600;white-space:normal}tr:last-child td{border-bottom:0}td:last-child,th:last-child{border-right:0}tbody tr:nth-child(even){background:#fbfcfe}code{font:12px/1.7 Consolas,monospace;background:#edf2f8;padding:2px 4px;border-radius:4px;overflow-wrap:anywhere}pre{overflow:auto;padding:20px;background:#182a41;border-radius:7px;color:#e4eefc;font-size:12px;line-height:1.85}pre code{background:none;padding:0;color:inherit;white-space:pre}input[type=checkbox]{accent-color:var(--blue)}.source-link{border-top:1px solid var(--line);padding-top:20px;margin-top:36px;font-size:12px}.footer{color:var(--muted);font-size:12px;margin:18px 2px}body:has(#doc4:not([hidden])) .stat:last-child{border-color:#b9cdeb}button:focus-visible,a:focus-visible,input:focus-visible{outline:3px solid #84b4f4;outline-offset:3px}
@media(max-width:900px){header{padding:0 17px;height:68px}.brand{font-size:16px}.mobile-toggle{display:block}.layout{display:block}.sidebar{display:none;position:fixed;z-index:9;background:#f4f7fb;width:280px;top:68px;height:calc(100vh - 68px);box-shadow:8px 8px 30px #17355a24}.sidebar.open{display:block}.content{padding:20px 16px 40px}.stats{gap:8px}.stat{padding:10px}.stat b{font-size:22px}.stat span{font-size:11px}article{padding:22px 20px}h1{font-size:24px}.actions button{font-size:12px;padding:6px 9px}.table-scroll{margin:16px 0}}
@media print{body{background:#fff;font-size:10pt}header,.sidebar,.stats,.toc,.source-link,.footer{display:none}.layout{display:block}.content{padding:0}article{border:0;box-shadow:none;padding:0}h1{font-size:22pt}h2{font-size:16pt;break-after:avoid}h3{font-size:13pt;break-after:avoid}.table-scroll{overflow:visible}table{min-width:0;font-size:8pt}th,td{padding:6px}tr{break-inside:avoid}pre{white-space:pre-wrap}pre code{white-space:pre-wrap}@page{size:A4 landscape;margin:15mm}}
</style></head><body><header><div class="brand">MOP平台开发文档<small>合同与原型对应的实施步骤 · 待确认规则留空</small></div><div class="actions"><button class="mobile-toggle" id="menu" aria-label="展开文档目录">目录</button><button id="print">打印当前文档</button></div></header><div class="layout"><aside class="sidebar" id="sidebar"><label for="search">搜索文档全文</label><input id="search" type="search" placeholder="例如：SLA、Q02、备份"><nav>${nav}</nav><p class="no-results" id="no-results" hidden>没有匹配的文档</p><p class="side-note">客户结论统一写入第04篇。<br>未确认项保持空值；依赖动作暂停，独立部分先开发。<br><br>本版为开发设计，实际实现与业务验收另行留证。</p></aside><main class="content"><div class="stats"><div class="stat"><b>128</b><span>合同功能逐项追踪</span></div><div class="stat"><b>31</b><span>原型页面覆盖</span></div><div class="stat"><b>31</b><span>客户确认事项</span></div><div class="stat"><b>${cfg.settings.length}</b><span>全局及租户参数登记</span></div></div>${sections}<div class="footer">本地阅读版 · 可编辑源文件位于 doc/development · 文档结构检查${summary.passed ? '通过' : '未通过'}，不代表业务验收。</div></main></div><script>
const sourceText=${JSON.stringify(Object.fromEntries(data.map(d=>[d.id,d.md.toLowerCase()]))).replaceAll('<','\\u003c')};
function showDoc(id,scroll=true){if(!document.getElementById(id)?.matches('article'))return;document.querySelectorAll('article').forEach(a=>a.hidden=a.id!==id);document.querySelectorAll('.nav-item').forEach(b=>b.classList.toggle('active',b.dataset.doc===id));document.getElementById('sidebar').classList.remove('open');if(scroll)window.scrollTo({top:0,behavior:'instant'});history.replaceState(null,'','#'+id);}
document.querySelectorAll('[data-doc]').forEach(b=>b.addEventListener('click',()=>showDoc(b.dataset.doc)));
document.querySelectorAll('[data-open-doc]').forEach(a=>a.addEventListener('click',e=>{e.preventDefault();showDoc(a.dataset.openDoc)}));
document.querySelectorAll('[data-section]').forEach(a=>a.addEventListener('click',e=>{e.preventDefault();document.getElementById(a.dataset.section).scrollIntoView({behavior:'instant'})}));
document.getElementById('search').addEventListener('input',e=>{const q=e.target.value.trim().toLowerCase();let n=0;document.querySelectorAll('.nav-item').forEach(b=>{b.hidden=!!q&&!sourceText[b.dataset.doc].includes(q);b.style.display=b.hidden?'none':'flex';if(!b.hidden)n++});document.getElementById('no-results').hidden=n>0});
document.getElementById('menu').addEventListener('click',()=>document.getElementById('sidebar').classList.toggle('open'));
document.getElementById('print').addEventListener('click',()=>window.print());
const initial=location.hash.slice(1);if(initial.startsWith('doc'))showDoc(initial.split('-')[0],false);
</script></body></html>`;
const out = path.join(project, 'output/documents/MOP平台开发文档.html');
fs.mkdirSync(path.dirname(out), {recursive:true});
fs.writeFileSync(out, html, 'utf8');
console.log(JSON.stringify({html:out,documents:data.length,bytes:Buffer.byteLength(html)}));
