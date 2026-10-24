import {it,expect,vi} from 'vitest'
import {ApiError} from '../src/api/client'
import {createEnrollmentCommands,enrollmentPlanRequest,readEnrollment} from '../src/utils/monitoringEnrollments'
import {emptyMonitoringDraft,monitoringPresets} from '../src/utils/monitoringOnboarding'
const tenant='79000000-0000-4000-8000-000000000001',actor='79000000-0000-4000-8000-000000000002',id='79000000-0000-4000-8000-000000000003',target='79000000-0000-4000-8000-000000000004'
const scope=()=>({key:tenant+actor,tenant})
function storage(){const m=new Map<string,string>();return{getItem:(k:string)=>m.get(k)??null,setItem:(k:string,v:string)=>{m.set(k,v)},removeItem:(k:string)=>{m.delete(k)}}}
function row(phase='REQUESTED',version=1){return{state:{version,phase,document:{id,tenant_id:tenant,created_by:actor,catalog_sha256:'a'.repeat(64),configuration_sha256:'b'.repeat(64),configuration:'{}',assets:[{id:actor,version:1,management_ip:'192.0.2.1'}]},last_probe:null},targets:[{target_id:target,configuration_sha256:'c'.repeat(64),configuration:{version:1,targets:[{id:target,tenant_id:tenant,asset_id:actor,enabled:false}]},test_authorized:false}],catalog_current:true,external_configuration_applied:false,continuous_collection_verified:false,device_identity_verified:false,health_verified:false}}
it('never forwards local URLs, credentials or activation to the durable server plan',()=>{
 const draft={...emptyMonitoringDraft(),assetId:actor,host:'198.51.100.2',authProfile:'local-secret-like-input',enabled:true}
 const plan=enrollmentPlanRequest(draft,'',monitoringPresets);expect(plan.assets[0]?.profiles[0]).toEqual({preset_id:'linux-node',port:9100,scheme:'http'});expect(JSON.stringify(plan)).not.toContain('198.51.100.2');expect(JSON.stringify(plan)).not.toContain('local-secret');expect(JSON.stringify(plan)).not.toContain('enabled')
})
it('SNMP and blackbox require a server collector name, not an endpoint',()=>{
 const draft={...emptyMonitoringDraft(),assetId:actor,presetId:'network-snmp'}
 expect(()=>enrollmentPlanRequest(draft,'',monitoringPresets)).toThrow();expect(()=>enrollmentPlanRequest(draft,'http://192.0.2.1',monitoringPresets)).toThrow();expect(enrollmentPlanRequest(draft,'snmp_site_a',monitoringPresets).assets[0]?.profiles[0]).toEqual({preset_id:'network-snmp',collector:'snmp_site_a'})
})
it('rejects cross-tenant and activation claims in received registration data',()=>{expect(()=>readEnrollment(row(),actor)).toThrow();expect(()=>readEnrollment({...row(),health_verified:true},tenant)).toThrow();const v=row();v.targets[0]!.configuration.targets[0]!.enabled=true;expect(()=>readEnrollment(v,tenant)).toThrow()})
it('retains original command across reload and denied replay',async()=>{
 const journal=storage(),request=vi.fn().mockRejectedValue(new ApiError(503,'UNAVAILABLE','unit','unit'));let command=createEnrollmentCommands(scope,journal,{request})
 await command.send(`/monitoring-onboarding/enrollments/${id}/actions`,{action:'APPROVE',expected_version:1,reason:null});const original=JSON.parse(JSON.stringify(command.pending.value));command.dispose();command=createEnrollmentCommands(scope,journal,{request});expect(command.pending.value).toEqual(original)
 request.mockRejectedValueOnce(new ApiError(403,'FORBIDDEN','unit','unit'));await command.retry();expect(command.pending.value).not.toBeNull()
 request.mockResolvedValue(row('APPROVED',2));expect(await command.retry()).toBe(true);expect(request.mock.calls[2][1].idempotencyKey).toBe(original.key);expect(command.pending.value).toBeNull()
})
it('wrong phase or version acknowledgment never clears the original command',async()=>{
 for(const result of [row('REQUESTED',2),row('APPROVED',3)]){const request=vi.fn().mockResolvedValue(result),command=createEnrollmentCommands(scope,storage(),{request});await command.send(`/monitoring-onboarding/enrollments/${id}/actions`,{action:'APPROVE',expected_version:1,reason:null});expect(command.pending.value).not.toBeNull()}
})
it('does not send commands when durable storage fails',async()=>{
 const journal=storage(),request=vi.fn();journal.setItem=()=>{throw new Error('storage denied')};const command=createEnrollmentCommands(scope,journal,{request});await command.send(`/monitoring-onboarding/enrollments/${id}/actions`,{action:'APPROVE',expected_version:1,reason:null});expect(request).not.toHaveBeenCalled()
})
it('does not automatically retry a restored command',async()=>{const journal=storage(),request=vi.fn().mockRejectedValue(new Error('network')),first=createEnrollmentCommands(scope,journal,{request});await first.send(`/monitoring-onboarding/enrollments/${id}/actions`,{action:'APPROVE',expected_version:1,reason:null});first.dispose();createEnrollmentCommands(scope,journal,{request});expect(request).toHaveBeenCalledOnce()})
