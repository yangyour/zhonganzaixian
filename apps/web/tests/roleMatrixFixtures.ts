import {createHash} from 'node:crypto'
import {catalogRoleIds,catalogDimensionIds} from '../src/utils/capabilityCatalogReader'
import type {RolePage} from '../src/utils/projectIdentity'
export const matrixTenant='79000000-0000-4000-8000-000000000001'
export function matrixFixture():RolePage {
 const entries=[{id:'assets:read',label:'读取资产',dimension_ids:['ASSETS_METRICS'],declared_by_role:{viewer:true,tadmin:true,msp:true,padmin:true}},{id:'roles:read',label:'读取角色',dimension_ids:['MEMBER_ADMIN'],declared_by_role:{viewer:false,tadmin:false,msp:false,padmin:true}}]
 const canonical='mop-capability-declarations-v1\nviewer|tadmin|msp|padmin\n'+entries.map(e=>e.id+'|'+e.dimension_ids.join(',')+'|'+catalogRoleIds.map(r=>e.declared_by_role[r]?'1':'0').join('')+'\n').join('')
 return {tenant_id:matrixTenant,permission_catalog:{declaration_hash:createHash('sha256').update(canonical).digest('hex'),roles:catalogRoleIds.map((id,i)=>({id,label:['客户观察者','客户管理员','MSP值班工程师','平台管理员'][i]!})),dimensions:catalogDimensionIds.map((id,i)=>({id,label:['资产与指标','告警与工单','模板与自动化','平台管理','成员管理'][i]!,capability_ids:entries.filter(e=>e.dimension_ids.includes(id)).map(e=>e.id)})),entries},items:catalogRoleIds.map((role,i)=>({id:'79000000-0000-4000-8000-'+String(i+10).padStart(12,'0'),tenant_id:matrixTenant,name:'合成角色 '+role,description:'Not exported private description',base_role:role,permissions:entries.filter(e=>e.declared_by_role[role]).map(e=>e.id),builtin:true,enabled:true,version:1,assigned_members:1,created_at:'2026-10-23T00:00:00Z',updated_at:'2026-10-23T00:00:00Z'}))}
}
