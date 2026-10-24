import type {ReportDetail} from './inspectionQuarterlyReports'

/** Presentation only: caller must freshly authorize and validate the frozen detail first. */
export const reportPreviewMaxBytes=8*1024*1024
const escape=(value:unknown)=>String(value??'未配置（null）').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]!))
export function renderReportPreview(report:ReportDetail):string {
 const output=report.frozen_document.output,parts:string[]=[];let bytes=0
 const add=(html:string)=>{bytes+=new TextEncoder().encode(html).length;if(bytes>reportPreviewMaxBytes)throw new Error('草稿预览超过 8 MiB 安全上限；未导出不完整文件，请在页面分组查看。');parts.push(html)}
 const fact=(label:string,value:unknown)=>add(`<dt>${escape(label)}</dt><dd>${escape(value)}</dd>`)
 add(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"><meta name="referrer" content="no-referrer"><title>DRAFT · ${escape(report.label)}</title><style>body{font:16px/1.7 system-ui,sans-serif;color:#182c40;background:#fff;max-width:960px;margin:auto;padding:24px;overflow-wrap:anywhere}h1{font-size:26px}h2{font-size:21px}h3{font-size:18px}.warning{border:2px solid #80551c;padding:16px;color:#623e0d}dt{font-weight:bold}dd{margin:0 0 12px}article{border-top:1px solid #66788a;padding:16px 0}li{margin-bottom:8px}pre{white-space:pre-wrap;font:inherit}@media(max-width:480px){body{padding:12px}h1{font-size:22px}}@media print{h1,h2,h3{break-after:avoid}body{max-width:none;padding:0}.warning{border-color:#000;color:#000}}</style></head><body><main><h1>${escape(report.label)} · ${report.year} Q${report.quarter}</h1><p class="warning">DRAFT 草稿预览 · 未批准 · 不可发布 · 未发布。来源为人工自述，未核验；证据访问未授权。租户资产总量未知，不代表覆盖率或客户验收。离线副本包含租户报告数据，请妥善保存；后续权限撤销无法收回已下载副本。</p><p>此文件是冻结报告输出的阅读预览，不包含全部来源原文或附件，不是完整备份、正式 PDF 或发布凭证。</p><h2>冻结身份与来源摘要</h2><dl>`)
 for(const [label,value] of Object.entries({报告ID:report.id,租户ID:report.tenant_id,记录时间原值:report.recorded_at,来源集合:report.source_collection_id,来源文档SHA256:report.source_document_sha256,来源清单SHA256:report.source_manifest_sha256,报告输入SHA256:report.report_sha256,冻结文档SHA256:report.document_sha256}))fact(label,value)
 add('</dl><h2>未批准配置</h2><dl>');for(const [key,value]of Object.entries(report.frozen_document.configuration))fact(key,value)
 add('</dl><h2>明确选择来源汇总（非租户覆盖率）</h2><dl>');for(const [key,value]of Object.entries(output.summary))fact(key,value)
 add('</dl><h2>待解决事项</h2><ul>');for(const reason of output.pending_reasons)add(`<li>${escape(reason)}</li>`)
 add('</ul><h2>全部资产输出</h2>');if(!output.rows.length)add('<p>明确空选择，不代表零库存或巡检已完成。</p>')
 for(const row of output.rows){
  add(`<article><h3>${escape(row.asset_name??'未选定来源')} · ${escape(row.status)}</h3><p>资产 ID：${escape(row.asset_id)}</p><p>${row.selected?'已选定一个冻结修订':'未选定来源，不计作正常'}</p><h4>候选修订摘要</h4><ul>`)
  for(const candidate of row.candidates)add(`<li>${candidate.worksheetId===row.selected?.worksheetId&&candidate.version===row.selected.version?'选定：':''}${escape(candidate.worksheetId)} · v${candidate.version}<br>快照 SHA256：${escape(candidate.snapshotSha256)}<br>内容 SHA256：${escape(candidate.contentSha256)}</li>`)
  add('</ul><h4>选定检查项</h4>');if(!row.items.length)add('<p>没有选定检查项，不代表零项已完成。</p>')
  for(const item of row.items)add(`<section><h5>${escape(item.item.section)} · ${escape(item.item.label)}</h5><p>${escape(item.item.instruction)}</p><p>结果：${escape(item.observation?.result??'未填写')}</p><pre>${escape(item.observation?.note??'无备注')}</pre></section>`)
  add('</article>')
 }
 add(`<p>关注及需处理项共 ${output.findings.length} 项，已包含在上方选定检查项中。证据引用不作为可点击链接导出。</p></main></body></html>`)
 return parts.join('')
}
/** Synchronous activation with bounded URL lifetime; does not claim the user saved the file. */
export function downloadReportPreview(html:string,id:string):void {
 if(!/^[0-9a-f-]{36}$/.test(id))throw new Error('报告标识无效')
 const url=URL.createObjectURL(new Blob([html],{type:'text/html;charset=utf-8'})),anchor=document.createElement('a')
 try{anchor.href=url;anchor.download=`quarterly-draft-${id}.html`;anchor.hidden=true;document.body.append(anchor);anchor.click()}finally{anchor.remove();setTimeout(()=>URL.revokeObjectURL(url),1000)}
}
