import { expect,it } from 'vitest'
import fixture from '../../../fixtures/cmdb-type-catalog-controller.json'
import { readCmdbTypeCatalog } from '../src/utils/cmdbTypeCatalog'
const fresh=()=>structuredClone(fixture.data),tenant=fixture.data.tenant_id
it('accepts the actual Java controller fixture with all attributes and sixteen subtypes',()=>{const v=readCmdbTypeCatalog(fresh(),tenant);expect(v.attributes).toHaveLength(95);expect(v.subtypes).toHaveLength(16)})
it('rejects cross-tenant and invented activation claims',()=>{for(const patch of [{tenant_id:'other'},{runtime_publication:true},{asset_mutations:true},{production_approved:true}])expect(()=>readCmdbTypeCatalog({...fresh(),...patch},tenant)).toThrow()})
it('rejects unknown versions and extra fields',()=>{for(const patch of [{catalog_version:'next'},{catalog_hash:'bad'},{credential_value:'secret'}])expect(()=>readCmdbTypeCatalog({...fresh(),...patch},tenant)).toThrow()})
it('duplicate identifiers and wrong parent are not silently repaired',()=>{let v=fresh();v.subtypes[0]!.parent='FW';expect(()=>readCmdbTypeCatalog(v,tenant)).toThrow();v=fresh();v.subtypes[0]=v.subtypes[1]!;expect(()=>readCmdbTypeCatalog(v,tenant)).toThrow();v=fresh();v.attributes[0]=v.attributes[1]!;expect(()=>readCmdbTypeCatalog(v,tenant)).toThrow()})
it('ADC mapping cannot be changed to resolved by an unsupported response',()=>{const v=fresh();v.subtypes.find(x=>x.code==='fw_adc')!.monitoring_mapping_resolved=true;expect(()=>readCmdbTypeCatalog(v,tenant)).toThrow()})
it('unknown health and all four independent dimensions are required',()=>{const v=fresh();v.state_dimensions.find(x=>x.field==='health_state')!.values.find(x=>x.code==='unknown')!.code='ok';expect(()=>readCmdbTypeCatalog(v,tenant)).toThrow()})
it('unresolved issues cannot be removed or silently approved',()=>{const v=fresh();v.unresolved.pop();expect(()=>readCmdbTypeCatalog(v,tenant)).toThrow();const approved=fresh() as any;approved.unresolved[0].resolution='approved';expect(()=>readCmdbTypeCatalog(approved,tenant)).toThrow()})
it('returns an independent copy of the received catalog',()=>{const raw=fresh(),v=readCmdbTypeCatalog(raw,tenant);raw.primary_types[0]!.label='changed';expect(v.primary_types[0]!.label).not.toBe('changed')})
