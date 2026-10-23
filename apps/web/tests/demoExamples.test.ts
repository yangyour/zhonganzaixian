// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import { api } from '../src/api/client'
import { session, switchingTenant } from '../src/stores/session'
import { useDemoExample } from '../src/utils/useDemoExample'
import type { Session } from '../src/api/types'

const auth: Session = { user:{id:'22222222-2222-4222-8222-222222222222',display_name:'DEMO',role:'padmin'},tenant:{id:'11111111-1111-4111-8111-111111111111',name:'DEMO'},allowed_tenants:[],capabilities:['configurations:read'],permission_version:1,csrf_token:'synthetic',mode:'remote_test',identity_source:'project_local',demo_examples_enabled:true }
const response = () => ({tenant_id:auth.tenant.id,actor_id:auth.user.id,page:'threshold-diagnostics',available:true,source:'EXPLICIT_DEMO_INPUT',input:{sample:1},business_data_written:false,provider_accessed:false})
let app:App, host:HTMLElement, text=ref(''), hook:ReturnType<typeof useDemoExample>
async function settle(){for(let i=0;i<8;i++){await Promise.resolve();await nextTick()}}
async function mount(apply:(input:{sample:number})=>Promise<unknown>|unknown=(input)=>{text.value=String(input.sample)}){
 host=document.createElement('div');document.body.append(host)
 app=createApp({setup(){hook=useDemoExample('threshold-diagnostics',ref(true),()=>text.value,apply);return()=>h('div')}})
 app.mount(host);await settle()
}
beforeEach(()=>{session.value=structuredClone(auth);switchingTenant.value=false;text=ref('')})
afterEach(()=>{app?.unmount();host?.remove();session.value=null;vi.restoreAllMocks()})
it('does not request or replace input for ordinary real sessions',async()=>{session.value={...auth,demo_examples_enabled:undefined};const request=vi.spyOn(api,'request');await mount();expect(request).not.toHaveBeenCalled();expect(text.value).toBe('')})
it('loads explicit demo input and tracks subsequent user edits as dirty',async()=>{vi.spyOn(api,'request').mockResolvedValue(response());await mount();expect(text.value).toBe('1');expect(hook.pristine.value).toBe(true);text.value='my edit';expect(hook.pristine.value).toBe(false)})
it('does not overwrite edits made while loading',async()=>{let done!:(value:unknown)=>void;vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>done=r));await mount();text.value='typed';done(response());await settle();expect(text.value).toBe('typed');expect(hook.loaded.value).toBe(false)})
it('rejects a response belonging to another tenant',async()=>{vi.spyOn(api,'request').mockResolvedValue({...response(),tenant_id:'33333333-3333-4333-8333-333333333333'});await mount();expect(text.value).toBe('');expect(hook.loaded.value).toBe(false)})
it('never marks edits during calculation as pristine',async()=>{vi.spyOn(api,'request').mockResolvedValue(response());let done!:()=>void;await mount(()=>{text.value='1';return new Promise<void>(r=>done=r)});text.value='typed while calculating';done();await settle();expect(hook.pristine.value).toBe(false)})
it('aborts and ignores an old response after a tenant switch',async()=>{let done!:(value:unknown)=>void;const request=vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>done=r));await mount();session.value={...auth,tenant:{id:'33333333-3333-4333-8333-333333333333',name:'real'},demo_examples_enabled:false};await settle();expect(request.mock.calls[0]?.[1]?.signal?.aborted).toBe(true);done(response());await settle();expect(text.value).toBe('');expect(hook.loaded.value).toBe(false)})
