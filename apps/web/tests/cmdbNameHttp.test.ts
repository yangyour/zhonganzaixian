import {it,expect} from 'vitest'
import capture from '../../../fixtures/cmdb-name-http-synthetic-20261021.json'
import type {AssetImportSource} from '../src/api/assetImportTypes'
import {readCmdbSourcePreflight} from '../src/utils/cmdbSourcePreflight'
const source=capture.source as unknown as AssetImportSource
it('actual loopback dictionary and atomic name-check responses satisfy production readers',()=>{
 expect(readCmdbSourcePreflight(capture.dictionary,source).database_checks).toBe('NOT_RUN')
 const checked=readCmdbSourcePreflight(capture.name_check,source,true)
 expect(checked.records).toBe(4);expect(checked.error_rows).toBe(1);expect(checked.can_import).toBe(false)
 expect(checked.findings.filter(f=>f.code==='EXISTING_ASSET_NAME').map(f=>f.source_row)).toEqual([2])
})
it('actual result cannot be reused after source-version/hash changes or as dictionary-only',()=>{
 expect(()=>readCmdbSourcePreflight(capture.name_check,source)).toThrow()
 expect(()=>readCmdbSourcePreflight(capture.name_check,{...source,version:source.version+1},true)).toThrow()
 expect(()=>readCmdbSourcePreflight(capture.name_check,{...source,parsed_artifact:{...source.parsed_artifact!,parse_hash:'f'.repeat(64)}},true)).toThrow()
})
it('actual PostgreSQL source snapshot satisfies the strengthened import reader',async()=>{
 const {validateAssetImportSource}=await import('../src/utils/assetImportReadback')
 expect(validateAssetImportSource(source,{tenantId:source.tenant_id,actorId:source.created_by},source.id)).toBe(source)
})
