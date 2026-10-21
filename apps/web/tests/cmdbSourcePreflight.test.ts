import {it,expect} from 'vitest'
import {readCmdbSourcePreflight} from '../src/utils/cmdbSourcePreflight'
import {cmdbSource,response} from './fixtures/cmdbSource'
it('accepts actual Java controller wire fixture, retaining non-importable state',()=>{const r=readCmdbSourcePreflight(response.data,cmdbSource());expect(r.can_import).toBe(false);expect(r.findings).not.toBe(response.data.findings)})
it('rejects stale version/hash and foreign scope',()=>{for(const patch of [{source_version:4},{parse_hash:'b'.repeat(64)},{tenant_id:'wrong'},{source_id:'wrong'}])expect(()=>readCmdbSourcePreflight({...response.data,...patch},cmdbSource())).toThrow()})
it('rejects manufactured import approval and omitted checks',()=>{for(const patch of [{can_import:true},{database_checks:'PASS'},{asset_mutations:true},{production_approved:true},{extra:'x'}])expect(()=>readCmdbSourcePreflight({...response.data,...patch},cmdbSource())).toThrow()})
it('findings must reference actual source records and reconcile counts',()=>{
 const finding={record:2,source_row:2,column:16,level:'ERROR',code:'SUBTYPE_PARENT_MISMATCH',message:'不匹配'}
 const r={...response.data,status:'ERROR',error_rows:1,findings:[finding]};expect(readCmdbSourcePreflight(r,cmdbSource()).error_rows).toBe(1)
 for(const patch of [{record:3},{source_row:9},{column:33},{code:'FAKE'}])expect(()=>readCmdbSourcePreflight({...r,findings:[{...finding,...patch}]},cmdbSource())).toThrow()
 expect(()=>readCmdbSourcePreflight({...r,error_rows:0},cmdbSource())).toThrow()
})
it('unparsed current source cannot accept older preflight results',()=>{const s=cmdbSource();s.parse_status='PENDING';expect(()=>readCmdbSourcePreflight(response.data,s)).toThrow()})

const named=()=>({...structuredClone(response.data),scope:'SOURCE_DICTIONARY_AND_ASSET_NAMES',database_checks:'EXACT_ASSET_NAMES_ONLY'})
it('database mode is explicit and cannot accept a dictionary-only response',()=>{
 expect(readCmdbSourcePreflight(named(),cmdbSource(),true).can_import).toBe(false)
 expect(()=>readCmdbSourcePreflight(named(),cmdbSource())).toThrow()
 expect(()=>readCmdbSourcePreflight(response.data,cmdbSource(),true)).toThrow()
})
it('exact asset name errors must be unique source rows at column zero',()=>{
 const f={record:2,source_row:2,column:0,level:'ERROR',code:'EXISTING_ASSET_NAME',message:'当前租户已存在相同名称'}
 const value={...named(),status:'ERROR',error_rows:1,findings:[f]}
 expect(readCmdbSourcePreflight(value,cmdbSource(),true).error_rows).toBe(1)
 for(const patch of [{column:1},{level:'WARNING'},{record:99}])expect(()=>readCmdbSourcePreflight({...value,findings:[{...f,...patch}]},cmdbSource(),true)).toThrow()
 expect(()=>readCmdbSourcePreflight({...value,findings:[f,f]},cmdbSource(),true)).toThrow()
})
