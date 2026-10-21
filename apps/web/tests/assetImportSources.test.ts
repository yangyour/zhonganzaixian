import {it,expect} from 'vitest'
import {readImportSourcePage} from '../src/utils/assetImportSources'
import {page,owner,tenant,item,id} from './assetImportSources.fixture'
const scope={tenantId:tenant,actorId:owner}
it('reads bounded descending pages without raw data and binds cursor to last item',()=>{const raw=page();expect(readImportSourcePage(raw,scope).items).not.toBe(raw.items);expect(readImportSourcePage(page(20,3,false),scope,20,raw.items.at(-1)).items).toHaveLength(3)})
it.each([{tenant_id:owner},{created_by:tenant},{scope:'ALL'},{can_import:true},{raw_cells:[]},{next_cursor:id(1)}])('rejects wrong scope or extra data %j',patch=>{expect(()=>readImportSourcePage({...page(),...patch},scope)).toThrow()})
it('rejects repeated reordered and out-of-bound records',()=>{for(const items of [[item(0),item(0)],[item(2),item(1)],Array.from({length:21},(_,i)=>item(i))])expect(()=>readImportSourcePage({...page(),items,next_cursor:null},scope)).toThrow();expect(()=>readImportSourcePage(page(18,2,false),scope,20,item(19))).toThrow()})
it.each([{version:0},{original_name:'../secret.csv'},{created_at:'2026-02-30T00:00:00Z'},{updated_at:'2020-01-01T00:00:00Z'},{mapping_saved:true},{parse_status:'PARSED'},{size_bytes:0},{raw_cells:[]}])('rejects inconsistent metadata %j',patch=>{expect(()=>readImportSourcePage({...page(0,1,false),items:[{...item(0),...patch}]},scope)).toThrow()})
it('allows explicit empty final page but not a fabricated empty next cursor',()=>{expect(readImportSourcePage(page(0,0,false),scope).items).toEqual([]);expect(()=>readImportSourcePage({...page(0,0,false),next_cursor:id(100)},scope)).toThrow()})
