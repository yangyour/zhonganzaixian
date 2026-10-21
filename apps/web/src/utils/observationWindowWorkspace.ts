import {ApiError,isCancelled,type ApiClient} from '../api/client'
import {parseStrictJsonValue} from './strictJsonValue'
import {readThresholdInput,type ThresholdCondition,type ThresholdContext} from './thresholdDiagnosticWorkspace'
import {utcInstantNanos} from './utcInstant'
export interface ObservationSample {recorded_at:string;state:'KNOWN'|'UNKNOWN'|'COLLECTOR_ERROR';value:string|null;unit:string|null}
export interface ObservationInput {condition:ThresholdCondition|null;hold_seconds:string|null;max_gap_seconds:string|null;observed_at:string;samples:ObservationSample[]}
export type ObservationStatus='CONFIG_PENDING'|'CONFLICT'|'NO_SAMPLES'|'STALE_LATEST'|'UNKNOWN_LATEST'|'NO_CURRENT_MATCH'|'MATCHING_SPAN_NOT_REACHED'|'MATCHING_SPAN_REACHED'
export interface ObservationResult {tenant_id:string;actor_id:string;permission_version:number;source:'EXPLICIT_INPUT';scope:'EXPLICIT_CANDIDATE_SAMPLES_ONLY';analysis_version:'EXPLICIT_MATCHING_OBSERVED_SPAN_V1';inputs:ObservationInput;status:ObservationStatus;reasons:string[];latest_sample_at:string|null;matching_span_started_at:string|null;matching_sample_count:number|null;observed_span_seconds:string|null;candidate_span_reached:boolean|null;continuity_claim:'OBSERVED_POINTS_ONLY_NOT_CONTINUOUS_PHYSICAL_PROOF';customer_confirmation:'UNVERIFIED';physical_range_check:'NOT_RUN';publishable:false;effective_rule:null;units_converted:false;monitoring_applied:false;event_state_updated:false;recovery_confirmed:false;writes_performed:false;tenant_authorization:'CURRENT_MEMBERSHIP_VERIFIED'}
export interface ObservationState {text:string;busy:boolean;result:ObservationResult|null;error:unknown}
export const emptyObservationState=():ObservationState=>({text:'',busy:false,result:null,error:null})
const bad=()=>new ApiError(0,'INVALID_OBSERVATION_DIAGNOSTIC','观察窗输入或返回未通过精确核对；请检查字段、时间、样本顺序和小数字符串，未确认任何实际恢复','—')
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const time=(v:unknown):v is string=>typeof v==='string'&&/^(?!0000)\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(v)&&utcInstantNanos(v)!==null
const validScope=(c:ThresholdContext|null):c is ThresholdContext=>!!c&&c.role==='padmin'&&c.canRead&&[c.tenantId,c.actorId].every(v=>/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v))&&Number.isSafeInteger(c.permissionVersion)&&c.permissionVersion>=1&&c.permissionVersion<=9007199254740990
export function readObservationInput(raw:unknown):ObservationInput{
 try{
  if(!exact(raw,['condition','hold_seconds','max_gap_seconds','observed_at','samples'])||!time(raw.observed_at)||!Array.isArray(raw.samples)||raw.samples.length>1000)throw bad()
  const parameters=readThresholdInput({direction:null,warning:raw.condition,critical:null,recovery:null,trigger_hold_seconds:raw.hold_seconds,recovery_hold_seconds:raw.max_gap_seconds})
  let previous:bigint|null=null;const observed=utcInstantNanos(raw.observed_at)!
  const samples=raw.samples.map(item=>{if(!exact(item,['recorded_at','state','value','unit'])||!time(item.recorded_at)||!['KNOWN','UNKNOWN','COLLECTOR_ERROR'].includes(String(item.state)))throw bad();const at=utcInstantNanos(item.recorded_at)!;if(at>observed||previous!==null&&at<=previous)throw bad();previous=at
   const parsed=readThresholdInput({direction:null,warning:{comparison:'GE',threshold:item.value,unit:item.unit},critical:null,recovery:null,trigger_hold_seconds:null,recovery_hold_seconds:null}).warning!
   if(item.state==='KNOWN'?(parsed.threshold===null||parsed.unit===null):parsed.threshold!==null)throw bad()
   return {recorded_at:item.recorded_at,state:item.state as ObservationSample['state'],value:parsed.threshold,unit:parsed.unit}
  })
  return {condition:parameters.warning,hold_seconds:parameters.trigger_hold_seconds,max_gap_seconds:parameters.recovery_hold_seconds,observed_at:raw.observed_at,samples}
 }catch{throw bad()}
}
export function parseObservationText(text:string){try{return readObservationInput(parseStrictJsonValue(text,{bytes:524288,depth:5,tokens:16000,stringLength:128}))}catch{throw bad()}}
function decimal(text:string){const split=text.split('.');return {coefficient:BigInt(split.join('')),scale:split[1]?.length??0}}
function compare(a:string,b:string){const x=decimal(a),y=decimal(b),scale=Math.max(x.scale,y.scale),left=x.coefficient*10n**BigInt(scale-x.scale),right=y.coefficient*10n**BigInt(scale-y.scale);return left<right?-1:left>right?1:0}
function seconds(from:string,to:string){const millis=(utcInstantNanos(to)!-utcInstantNanos(from)!)/1000000n;return `${millis/1000n}.${(millis%1000n).toString().padStart(3,'0')}`}
interface Facts {status:ObservationStatus;reasons:string[];latest:string|null;start:string|null;count:number|null;span:string|null;reached:boolean|null}
/** Independent expected facts are used ONLY to check a server response, never to manufacture local success. */
function expected(input:ObservationInput):Facts{
 const c=input.condition,complete=!!c&&c.comparison!==null&&c.threshold!==null&&c.unit!==null,reasons:string[]=[],latest=input.samples.at(-1)?.recorded_at??null
 if(!complete)reasons.push('CONDITION_INCOMPLETE')
 if(input.hold_seconds===null)reasons.push('HOLD_MISSING');else if(compare(input.hold_seconds,'0')<0)reasons.push('NEGATIVE_HOLD')
 if(input.max_gap_seconds===null)reasons.push('MAX_GAP_MISSING');else if(compare(input.max_gap_seconds,'0')<0)reasons.push('NEGATIVE_MAX_GAP')
 if(complete&&input.samples.some(s=>s.state==='KNOWN'&&s.unit!==c.unit))reasons.push('UNIT_MISMATCH')
 const empty={latest,start:null,count:null,span:null,reached:null}
 if(reasons.length)return {...empty,reasons,status:reasons.some(r=>['NEGATIVE_HOLD','NEGATIVE_MAX_GAP','UNIT_MISMATCH'].includes(r))?'CONFLICT':'CONFIG_PENDING'}
 if(latest===null)return {...empty,reasons,status:'NO_SAMPLES'}
 let start:string|null=null,previous:string|null=null,count=0,matching:boolean|null=null
 for(const sample of input.samples){
  if(previous!==null&&compare(seconds(previous,sample.recorded_at),input.max_gap_seconds!)>0){start=null;count=0}
  if(sample.state!=='KNOWN'){start=null;count=0;matching=null}
  else {const order=compare(sample.value!,c!.threshold!);matching=c!.comparison==='GT'?order>0:c!.comparison==='GE'?order>=0:c!.comparison==='LT'?order<0:order<=0;if(matching){start??=sample.recorded_at;count++}else{start=null;count=0}}
  previous=sample.recorded_at
 }
 if(compare(seconds(latest,input.observed_at),input.max_gap_seconds!)>0)return {...empty,reasons,status:'STALE_LATEST'}
 if(matching===null)return {...empty,reasons,status:'UNKNOWN_LATEST'}
 if(!matching)return {...empty,reasons,status:'NO_CURRENT_MATCH',count:0,span:'0',reached:false}
 const span=seconds(start!,latest),reached=compare(span,input.hold_seconds!)>=0
 return {latest,start,count,span,reached,reasons,status:reached?'MATCHING_SPAN_REACHED':'MATCHING_SPAN_NOT_REACHED'}
}
const keys=['tenant_id','actor_id','permission_version','source','scope','analysis_version','inputs','status','reasons','latest_sample_at','matching_span_started_at','matching_sample_count','observed_span_seconds','candidate_span_reached','continuity_claim','customer_confirmation','physical_range_check','publishable','effective_rule','units_converted','monitoring_applied','event_state_updated','recovery_confirmed','writes_performed','tenant_authorization']
export function readObservationResult(raw:unknown,context:ThresholdContext,submitted:ObservationInput):ObservationResult{
 if(!validScope(context)||!exact(raw,keys))throw bad()
 const input=readObservationInput(submitted),received=readObservationInput(raw.inputs),facts=expected(input)
 const sameTime=(actual:unknown,wanted:string|null)=>wanted===null?actual===null:time(actual)&&utcInstantNanos(actual)===utcInstantNanos(wanted)
 const sameSpan=facts.span===null?raw.observed_span_seconds===null:facts.status==='NO_CURRENT_MATCH'?raw.observed_span_seconds==='0':typeof raw.observed_span_seconds==='string'&&/^(?:0|[1-9][0-9]{0,39})(?:\.[0-9]{1,40})?$/.test(raw.observed_span_seconds)&&compare(raw.observed_span_seconds,facts.span)===0
 if(raw.tenant_id!==context.tenantId||raw.actor_id!==context.actorId||raw.permission_version!==context.permissionVersion||raw.source!=='EXPLICIT_INPUT'||raw.scope!=='EXPLICIT_CANDIDATE_SAMPLES_ONLY'||raw.analysis_version!=='EXPLICIT_MATCHING_OBSERVED_SPAN_V1'||JSON.stringify(input)!==JSON.stringify(received)||raw.status!==facts.status||JSON.stringify(raw.reasons)!==JSON.stringify(facts.reasons)||!sameTime(raw.latest_sample_at,facts.latest)||!sameTime(raw.matching_span_started_at,facts.start)||raw.matching_sample_count!==facts.count||!sameSpan||raw.candidate_span_reached!==facts.reached||raw.continuity_claim!=='OBSERVED_POINTS_ONLY_NOT_CONTINUOUS_PHYSICAL_PROOF'||raw.customer_confirmation!=='UNVERIFIED'||raw.physical_range_check!=='NOT_RUN'||raw.effective_rule!==null||raw.tenant_authorization!=='CURRENT_MEMBERSHIP_VERIFIED'||['publishable','units_converted','monitoring_applied','event_state_updated','recovery_confirmed','writes_performed'].some(k=>raw[k]!==false))throw bad()
 return structuredClone(raw) as unknown as ObservationResult
}
export function createObservationWorkspace(client:Pick<ApiClient,'request'>,changed:(state:ObservationState)=>void){
 let state=emptyObservationState(),context:ThresholdContext|null=null,epoch=0,abort:AbortController|null=null,alive=true
 const emit=()=>changed({...state,result:state.result?structuredClone(state.result):null})
 const invalidate=()=>{++epoch;abort?.abort();abort=null;state.busy=false;state.result=null;state.error=null}
 function reset(next:ThresholdContext|null){invalidate();context=alive&&validScope(next)?{...next}:null;state=emptyObservationState();emit()}
 function change(text:string){if(!alive||!context)return;invalidate();state.text=text;emit()}
 async function analyze(){if(!alive||!context||state.busy||!state.text)return false;invalidate();const turn=epoch,scope={...context};let input:ObservationInput;try{input=parseObservationText(state.text)}catch(error){state.error=error;emit();return false}const controller=new AbortController();abort=controller;state.busy=true;emit();try{const raw=await client.request('/monitoring/observation-window-diagnostics',{method:'POST',body:input,signal:controller.signal});if(!alive||turn!==epoch||controller.signal.aborted)return false;state.result=readObservationResult(raw,scope,input);return true}catch(error){if(alive&&turn===epoch&&!isCancelled(error))state.error=error;return false}finally{if(alive&&turn===epoch){state.busy=false;abort=null;emit()}}}
 emit();return {reset,change,analyze,cancel(){if(!alive)return;invalidate();emit()},dispose(){alive=false;reset(null)}}
}
