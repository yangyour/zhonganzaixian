import { createRequestId } from './browserCrypto'
import {isCancelled,type ApiClient} from '../api/client'
import {detachedWorkspaceState} from './detachedWorkspaceState'
import {writeFailureUncertain} from './writeFailure'
import {inspectionUuid,validInspectionContext} from './inspectionTemplates'
import {readSourceCollectionPayload,readSourceCollectionSummary,readSourceCollectionPage,readSourceCollectionDetail,sourceCollectionBad,sourceCollectionManifestBytes,sourceCollectionSha,type SourceCollectionContext,type SourceCollectionSummary,type SourceCollectionDetail,type SourceCollectionPage,type SourceCollectionCursor} from './inspectionSourceCollections'
export interface SourceCollectionAttempt {payload:string;key:string;state:'uncertain'|'accepted'|'rejected'}
export interface SourceCollectionState {page:SourceCollectionPage|null;selected:SourceCollectionDetail|null;receipt:SourceCollectionSummary|null;attempt:SourceCollectionAttempt|null;paged:boolean;busy:'read'|'write'|null;error:unknown}
export const emptySourceCollectionState=():SourceCollectionState=>({page:null,selected:null,receipt:null,attempt:null,paged:false,busy:null,error:null})
const same=(a:unknown,b:unknown):boolean=>{if(a===b)return true;if(Array.isArray(a)&&Array.isArray(b))return a.length===b.length&&a.every((v,i)=>same(v,b[i]));if(a&&b&&typeof a==='object'&&typeof b==='object'&&!Array.isArray(a)&&!Array.isArray(b)){const x=a as Record<string,unknown>,y=b as Record<string,unknown>;return Object.keys(x).length===Object.keys(y).length&&Object.keys(x).every(k=>Object.hasOwn(y,k)&&same(x[k],y[k]))}return false}
function metadata(detail:SourceCollectionDetail):SourceCollectionSummary{const {sources:_sources,diagnostics:_diagnostics,document_integrity:_integrity,...value}=detail;return {...value,projection:'METADATA_ONLY'}}
/** Only explicit user commands create a collection. Reads cannot resolve an unknown write or choose newer sources. */
export function createSourceCollectionWorkspace(client:Pick<ApiClient,'request'>,changed:(value:SourceCollectionState)=>void,newKey:()=>string=()=>createRequestId()){
  let context:SourceCollectionContext|null=null,state=emptySourceCollectionState(),epoch=0,abort:AbortController|null=null,disposed=false
  type ReadRequest={kind:'list'|'detail';next:boolean;id?:string;before?:SourceCollectionCursor}
  let lastRead:ReadRequest|null=null,readRetryable=false
  const snapshot=()=>detachedWorkspaceState(state),emit=()=>changed(snapshot()),unresolved=()=>state.busy==='write'||state.attempt?.state==='uncertain'
  function reset(next:SourceCollectionContext|null){lastRead=null;readRetryable=false;abort?.abort();abort=null;++epoch;context=!disposed&&validInspectionContext(next)?{...next}:null;state=emptySourceCollectionState();emit()}
  async function read(kind:'list'|'detail',next=false,id?:string){
    if(!context||disposed||state.busy)return false
    const before:SourceCollectionCursor|undefined=kind==='list'&&next&&state.page?.has_more?{time:state.page.next_before_recorded_at!,id:state.page.next_before_id!}:undefined
    if(next&&!before)return false
    return readRequest({kind,next,id,before})
  }
  async function readRequest(request:ReadRequest){
    if(!context||disposed||state.busy)return false
    const {kind,next,id,before}=request;lastRead=null;readRetryable=false
    if(kind==='detail'&&!inspectionUuid(id)){state={...state,selected:null,error:sourceCollectionBad()};emit();return false}
    lastRead=structuredClone(request)
    const turn=++epoch,scope={...context},controller=new AbortController();abort=controller;const current=()=>turn===epoch&&!disposed&&!controller.signal.aborted
    state={...state,...(kind==='list'?{page:null,selected:null,paged:next}:{selected:null}),busy:'read',error:null};emit()
    try{
      const params=new URLSearchParams({page_size:'15'});if(before){params.set('before_recorded_at',before.time);params.set('before_id',before.id)}
      const path=kind==='list'?'/inspection-source-collections?'+params:'/inspection-source-collections/'+id
      if(!current())return false
      const raw=await client.request(path,{signal:controller.signal});if(!current())return false
      if(kind==='list'){
        const page=readSourceCollectionPage(raw,scope,before),receipt=state.receipt,match=receipt&&page.items.find(row=>row.id===receipt.id)
        if(receipt&&match&&!same(receipt,match))throw sourceCollectionBad();state.page=page
      }else{
        const selected=await readSourceCollectionDetail(raw,scope,id!,current);if(!current())return false
        if(state.receipt?.id===selected.id&&!same(state.receipt,metadata(selected)))throw sourceCollectionBad();state.selected=selected
      }
      return true
    }catch(error){if(current()&&!isCancelled(error)){state.error=error;readRetryable=true}return false}
    finally{if(turn===epoch&&!disposed){state.busy=null;abort=null;emit()}}
  }
  async function send(original:SourceCollectionAttempt,retry:boolean){
    if(!context?.canWrite||disposed||state.busy)return false
    lastRead=null;readRetryable=false
    const attempt=structuredClone(original),scope={...context},turn=++epoch,controller=new AbortController();abort=controller;const current=()=>turn===epoch&&!disposed&&!controller.signal.aborted;let dispatched=false
    state={...state,attempt:{...attempt,state:'uncertain'},receipt:null,page:null,selected:null,busy:'write',error:null};emit()
    try{
      const payload=readSourceCollectionPayload(JSON.parse(attempt.payload)),expectedManifest=await sourceCollectionSha(sourceCollectionManifestBytes(scope.tenantId,payload.sources));if(!current())return false
      dispatched=true;const raw=await client.request('/inspection-source-collections',{method:'POST',body:structuredClone(payload),idempotencyKey:attempt.key,signal:controller.signal});if(!current())return false
      const receipt=readSourceCollectionSummary(raw,scope)
      if(receipt.created_by!==scope.actorId||receipt.label!==payload.label||receipt.source_count!==payload.sources.length||receipt.source_manifest_sha256!==expectedManifest)throw sourceCollectionBad()
      state={...state,receipt,attempt:{...attempt,state:'accepted'}};return true
    }catch(error){if(current())state={...state,error,attempt:{...attempt,state:retry||dispatched&&writeFailureUncertain(false,error)?'uncertain':'rejected'}};return false}
    finally{if(turn===epoch&&!disposed){state.busy=null;abort=null;emit()}}
  }
  async function submit(input:unknown){
    if(!context?.canWrite||disposed||state.busy||state.attempt)return false
    const generation=epoch
    try{const payload=readSourceCollectionPayload(input),key=newKey();if(generation!==epoch||disposed||!context?.canWrite||state.busy||state.attempt)return false;if(typeof key!=='string'||! /^[A-Za-z0-9._:-]{1,128}$/.test(key))throw sourceCollectionBad();return send({payload:JSON.stringify(payload),key,state:'uncertain'},false)}catch(error){if(generation===epoch&&!disposed){state.error=error;emit()}return false}
  }
  function cancelRead(){if(disposed||state.busy!=='read')return false;epoch++;abort?.abort();abort=null;readRetryable=lastRead!==null;state={...state,busy:null,error:null};emit();return true}
  emit();return {reset,cancelRead,canRetryRead:()=>!disposed&&readRetryable&&lastRead!==null&&!state.busy,retryRead:()=>readRetryable&&lastRead?readRequest(structuredClone(lastRead)):Promise.resolve(false),state:snapshot,unresolved,list:(next=false)=>read('list',next),select:(id:string)=>read('detail',false,id),submit,retry:()=>state.attempt?.state==='uncertain'?send(state.attempt,true):Promise.resolve(false),begin:()=>{if(!context?.canWrite||disposed||state.busy||unresolved())return false;lastRead=null;readRetryable=false;state={...state,attempt:null,receipt:null,error:null};emit();return true},dispose:()=>{if(disposed)return;disposed=true;reset(null)}}
}
