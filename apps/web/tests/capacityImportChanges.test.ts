import { expect, it } from 'vitest'
import { capacityImportChanges, capacityRuntimeFields, readCapacityRuntimeInputs } from '../src/utils/capacityRuntimeInputs'

const scope={tenantId:'74000000-0000-4000-8000-000000000001',actorId:'74000000-0000-4000-8000-000000000002',permissionVersion:1}
function snapshot(){
 return readCapacityRuntimeInputs({
  tenant_id:scope.tenantId,actor_id:scope.actorId,permission_version:1,checked_at:'2026-10-20T00:00:00Z',
  scope:'CURRENT_TENANT_CAPACITY_INPUTS',source:'postgresql',customer_confirmation_status:'UNVERIFIED',production_approved:false,measurement_verified:false,
  items:capacityRuntimeFields.map(f=>({configuration_key:f.key,status:'UNCONFIGURED',value:null,input_value:null,unit:f.unit,definition_hash:'a'.repeat(64),publication_id:null,publication_version:null,expires_at:null}))
 },scope)
}
it('lists all eight destinations and warns before clearing independent buffer and main inputs',()=>{
 const current={inputs:{bytes_per_value:'128',hosts:'42'},buffer:{bytes_per_value:'256',site_nvps:'12'}}
 const before=structuredClone(current),rows=capacityImportChanges(snapshot(),current)
 expect(rows).toHaveLength(8)
 expect(new Set(rows.map(r=>r.key)).size).toBe(8)
 expect(rows.filter(r=>r.cleared).map(r=>[r.key,r.before,r.after])).toEqual([
  ['inputs.bytes_per_value','128',''],['buffer.bytes_per_value','256','']
 ])
 expect(rows.some(r=>r.key==='inputs.hosts'||r.key==='buffer.site_nvps')).toBe(false)
 expect(current).toEqual(before)
})
it('preserves zero and arbitrarily precise strings without numeric conversion',()=>{
 const p=snapshot()
 p.items[4]={...p.items[4]!,status:'ACTIVE',value:'0',input_value:'0'}
 const current={inputs:{trend_ratio:'0',bytes_per_value:'9007199254740993.00000000000000001'},buffer:{}}
 const rows=capacityImportChanges(p,current)
 expect(rows.find(r=>r.key==='inputs.trend_ratio')).toMatchObject({before:'0',after:'0',changed:false,cleared:false})
 expect(rows.find(r=>r.key==='inputs.bytes_per_value')?.before).toBe('9007199254740993.00000000000000001')
})
it('shows exact transformed input, not the published multiplier, and leaves blank inputs unchanged',()=>{
 const p=snapshot()
 p.items[5]={...p.items[5]!,status:'ACTIVE',value:'1.2',input_value:'0.2'}
 const rows=capacityImportChanges(p,{inputs:{headroom_ratio:'0.5'},buffer:{}})
 expect(rows.find(r=>r.key==='inputs.headroom_ratio')).toMatchObject({before:'0.5',after:'0.2',changed:true,cleared:false})
 expect(rows.filter(r=>r.changed)).toHaveLength(1)
})
