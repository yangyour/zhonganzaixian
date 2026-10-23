import { ApiError } from '../api/client'
import { readRolePage } from './projectIdentity'
import { readCapabilityDefinition, catalogRoleIds } from './capabilityCatalogReader'
import { digestSha256 } from './browserCrypto'
const bad=(message='角色矩阵未通过完整性校验，未生成打印文件')=>new ApiError(200,'INVALID_ROLE_MATRIX',message,'—')
const escape=(text:unknown)=>String(text).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;').replaceAll("'",'&#39;')
const bounded=(s:unknown,max:number):s is string=>typeof s==='string'&&!!s.trim()&&Array.from(s).length<=max&&!/[\u0000-\u001f\u007f-\u009f]/.test(s)
const canonical=(v:unknown):string=>v===null||typeof v!=='object'?JSON.stringify(v):Array.isArray(v)?'['+v.map(canonical).join(',')+']':'{'+Object.keys(v).sort().map(k=>JSON.stringify(k)+':'+canonical((v as Record<string,unknown>)[k])).join(',')+'}'
/** Inert, complete saved-role review artifact. No account bindings, credentials, scripts or remote resources. */
export async function buildRoleMatrixPrint(raw:unknown,context:{tenantId:string;tenantName:string;generatedAt:string}){
 if(!bounded(context.tenantName,200)||!Number.isFinite(Date.parse(context.generatedAt)))throw bad()
 const page=readRolePage(raw,context.tenantId)
 if(page.items.length>200)throw bad('角色列表达到服务端截断哨兵，不能把部分列表打印为完整矩阵')
 if(page.items.length===0)throw bad('当前没有已保存的角色记录，不能生成完整配置矩阵')
 const catalog=await readCapabilityDefinition(page.permission_catalog),entries=new Map(catalog.entries.map(e=>[e.id,e])),ids=new Set<string>()
 const builtin=new Set<string>()
 for(const role of page.items){
  if(ids.has(role.id)||!bounded(role.name,100)||!Number.isSafeInteger(role.version)||role.version<1||role.version>9007199254740990
   ||!Number.isFinite(Date.parse(role.updated_at))||new Set(role.permissions).size!==role.permissions.length
   ||role.permissions.some(id=>typeof id!=='string'||!entries.get(id)?.declared_by_role[role.base_role]))throw bad()
  ids.add(role.id);if(role.builtin){if(builtin.has(role.base_role))throw bad();builtin.add(role.base_role)}
 }
 if(catalogRoleIds.some(id=>!builtin.has(id)))throw bad('四个内置基础角色未完整返回，不能生成完整配置矩阵')
 const roles=page.items.map(r=>({id:r.id,tenant_id:r.tenant_id,name:r.name,base_role:r.base_role,builtin:r.builtin,enabled:r.enabled,version:r.version,updated_at:r.updated_at,permissions:[...r.permissions].sort()})).sort((a,b)=>Number(b.builtin)-Number(a.builtin)||catalogRoleIds.indexOf(a.base_role)-catalogRoleIds.indexOf(b.base_role)||a.id.localeCompare(b.id))
 const snapshot={tenant_id:context.tenantId,catalog_declaration_hash:catalog.declaration_hash,catalog,roles,customer_signature:null,supplier_signature:null,customer_approval_status:'UNVERIFIED',subject_bindings_included:false}
 const bytes=new TextEncoder().encode(canonical(snapshot)),sha=Array.from(new Uint8Array(await digestSha256(bytes)),b=>b.toString(16).padStart(2,'0')).join('')
 const header=`<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"><title>当前租户角色权限矩阵</title><style>body{font-family:system-ui,sans-serif;color:#172b40;margin:24px;font-size:12px}h1{font-size:22px}h2{font-size:16px}table{border-collapse:collapse;width:100%;table-layout:fixed}th,td{border:1px solid #aab7c4;padding:6px;vertical-align:top;overflow-wrap:anywhere}th{text-align:left;background:#eef2f5}.hash{overflow-wrap:anywhere;font-family:monospace}.muted{color:#465a70}.group{margin-top:24px}.dimension th{background:#e2e9ef}.signatures{margin-top:28px;line-height:3}thead{display:table-header-group}tr{break-inside:avoid}@media print{@page{size:A4 landscape;margin:12mm}body{margin:0}.group{break-before:page}.signatures{break-inside:avoid}}</style></head><body>`
 let html=header+`<h1>当前租户角色权限矩阵</h1><p>租户：${escape(context.tenantName)}（${escape(context.tenantId)}）</p><p>文件生成时间（浏览器时钟）：${escape(context.generatedAt)}；已保存角色 ${roles.length} 个。</p><p>此文件是当前配置核对快照，未经双方签字确认；不是对象级访问许可，也不证明生产授权。停用角色的权限配置仅作为历史配置展示，不代表生效。没有人员绑定、密码或登录凭据。离线副本不会随撤权自动收回，请妥善保管。</p><p class="hash">配置快照 SHA256：${sha}</p><p class="hash">代码声明目录 SHA256：${catalog.declaration_hash}</p><p>使用浏览器打印功能打印此文件；每组最多四个角色，保留五个合同权限维度。</p>`
 const title=(r:typeof roles[number])=>`${escape(r.name)}<br><span class="muted">${escape(catalog.roles.find(x=>x.id===r.base_role)!.label)} · v${r.version} · ${r.enabled?'启用':'停用'}${r.builtin?' · 内置':''}<br>${escape(r.id)}<br>更新：${escape(r.updated_at)}</span>`
 for(let offset=0;offset<roles.length;offset+=4){const group=roles.slice(offset,offset+4);html+=`<section class="group"><h2>配置角色 ${offset+1}–${offset+group.length} / ${roles.length}</h2><table><caption>当前已保存权限配置，不是实时对象授权结果</caption><thead><tr><th scope="col">权限项</th>${group.map(r=>`<th scope="col">${title(r)}</th>`).join('')}</tr></thead><tbody>`
  for(const dimension of catalog.dimensions){html+=`<tr class="dimension"><th scope="rowgroup" colspan="${group.length+1}">${escape(dimension.label)}</th></tr>`
   const definitions=catalog.entries.filter(e=>e.dimension_ids.includes(dimension.id))
   if(!definitions.length)html+=`<tr><th scope="row">当前目录无声明项</th>${group.map(()=>'<td>无声明项，不推断授权</td>').join('')}</tr>`
   for(const entry of definitions)html+=`<tr><th scope="row">${escape(entry.label)}<br><span class="muted">${escape(entry.id)}</span></th>${group.map(r=>`<td>${!entry.declared_by_role[r.base_role]?'不在基础类别允许范围':r.permissions.includes(entry.id)?r.enabled?'已配置授予':'停用（配置保留）':'未配置授予'}</td>`).join('')}</tr>`
  }html+='</tbody></table></section>'
 }
 html+='<section class="signatures"><h2>双方核对签字（待完成）</h2><p>客户签字：________________　日期：________________</p><p>供应方签字：________________　日期：________________</p><p>确认范围及备注：________________________________________________</p></section></body></html>'
 const blob=new Blob([html],{type:'text/html;charset=utf-8'});if(blob.size>4*1024*1024)throw bad('完整矩阵超过打印技术上限，未生成截断文件')
 return{html,blob,sha256:sha,roleCount:roles.length,filename:`role-matrix-${context.tenantId}-${sha.slice(0,12)}.html`}
}
