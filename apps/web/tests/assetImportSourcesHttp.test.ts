import {afterEach,it,expect,vi} from 'vitest'
import fixture from '../../../fixtures/import-source-list-http-synthetic-20261021.json'
import {readImportSourcePage} from '../src/utils/assetImportSources'
import {createAssetImportWorkspace} from '../src/utils/assetImportWorkspace'
import {ApiClient} from '../src/api/client'
import {session,switchingTenant} from '../src/stores/session'
const scope={tenantId:fixture.first.tenant_id,actorId:fixture.first.created_by}
afterEach(()=>{session.value=null;switchingTenant.value=false})
it('actual loopback 20+3 source pages pass strict owner cursor reading',()=>{
 const first=readImportSourcePage(fixture.first,scope),second=readImportSourcePage(fixture.second,scope,20,first.items.at(-1))
 expect(first.items).toHaveLength(20);expect(second.items).toHaveLength(3);expect(new Set([...first.items,...second.items].map(i=>i.id)).size).toBe(23)
})
it('actual current policy and source can reopen with GET only and no local File',async()=>{
 session.value={user:{id:scope.actorId,display_name:'Synthetic',role:'msp'},tenant:{id:scope.tenantId,name:'Synthetic'},allowed_tenants:[],capabilities:['assets:write'],permission_version:1,csrf_token:'synthetic',mode:'remote_test',identity_source:'synthetic'};switchingTenant.value=false
 const transport=vi.fn(async (url:RequestInfo|URL)=>new Response(JSON.stringify({data:String(url).endsWith('/import-policy')?fixture.policy:fixture.source,trace_id:'synthetic'}),{status:200}));const client=new ApiClient(transport),workspace=createAssetImportWorkspace(client)
 try{await workspace.loadPolicy();expect(workspace.policyError.value).toBeNull();expect(await workspace.resume(fixture.source.id)).toBe(true);expect(workspace.file.value).toBeNull();expect(workspace.source.value?.id).toBe(fixture.source.id);expect(workspace.artifact.value?.rows).toHaveLength(4);expect(transport).toHaveBeenCalledTimes(2);for(const call of transport.mock.calls)expect((call as unknown as [unknown,RequestInit])[1]?.method).toBe('GET')}finally{workspace.dispose()}
})
