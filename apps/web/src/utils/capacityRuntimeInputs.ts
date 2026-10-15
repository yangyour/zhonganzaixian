import { ApiError } from '../api/client'
import { utcInstantNanos } from './utcInstant'
export const capacityRuntimeFields = [
  { key:'monitor.sample_interval_sec', label:'采样间隔', unit:'秒', target:'interval_seconds' },
  { key:'retention.history_days', label:'历史保留天数', unit:'天', target:'history_days' },
  { key:'retention.trend_days', label:'趋势保留天数', unit:'天', target:'trend_days' },
  { key:'capacity.bytes_per_value', label:'每值空间', unit:'字节/值', target:'bytes_per_value' },
  { key:'capacity.trend_ratio', label:'趋势比例', unit:'ratio_0_to_1', target:'trend_ratio' },
  { key:'capacity.headroom', label:'容量倍率', unit:'倍', target:'headroom_ratio' },
  { key:'capacity.proxy_outage_sec', label:'最长断网时间', unit:'秒', target:'outage_seconds' },
] as const
export const capacityRuntimeKeys = capacityRuntimeFields.map(f=>f.key)
export interface CapacityRuntimeItem { configuration_key:string;status:'ACTIVE'|'UNCONFIGURED'|'SCHEDULED'|'DISABLED'|'EXPIRED'|'DEFINITION_CHANGED'|'INVALID_VALUE'|'INCOMPATIBLE_MODEL';value:string|null;input_value:string|null;unit:string;definition_hash:string;publication_id:string|null;publication_version:number|null;expires_at:string|null }
export interface CapacityRuntimeInputs { tenant_id:string;actor_id:string;permission_version:number;checked_at:string;items:CapacityRuntimeItem[];scope:'CURRENT_TENANT_CAPACITY_INPUTS';source:'postgresql';customer_confirmation_status:'UNVERIFIED';production_approved:false;measurement_verified:false }
const exact=(v:any,keys:string[])=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const decimal=(v:unknown):v is string=>typeof v==='string'&&/^(?:0|[1-9][0-9]{0,39})(?:\.[0-9]{1,40})?$/.test(v)
const uuid=(v:unknown)=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
const rational=(v:string)=>{const [a,b='']=v.split('.');return {n:BigInt(a!+b),d:10n**BigInt(b.length)}}
const same=(a:string,b:string)=>{const x=rational(a),y=rational(b);return x.n*y.d===y.n*x.d}
const invalid=()=>new ApiError(0,'INVALID_CAPACITY_RUNTIME_INPUTS','运行容量参数未通过授权、单位或版本核对，请重新读取','—')
export function readCapacityRuntimeInputs(raw:unknown,scope:{tenantId:string;actorId:string;permissionVersion:number}):CapacityRuntimeInputs{
 const p=raw as CapacityRuntimeInputs
 if(!exact(p,['tenant_id','actor_id','permission_version','checked_at','items','scope','source','customer_confirmation_status','production_approved','measurement_verified'])||p.tenant_id!==scope.tenantId||p.actor_id!==scope.actorId||p.permission_version!==scope.permissionVersion||p.scope!=='CURRENT_TENANT_CAPACITY_INPUTS'||p.source!=='postgresql'||p.customer_confirmation_status!=='UNVERIFIED'||p.production_approved!==false||p.measurement_verified!==false||utcInstantNanos(p.checked_at)===null||!Array.isArray(p.items)||p.items.length!==7)throw invalid()
 p.items.forEach((item,i)=>{
  const field=capacityRuntimeFields[i]!
  if(!exact(item,['configuration_key','status','value','input_value','unit','definition_hash','publication_id','publication_version','expires_at'])||item.configuration_key!==field.key||item.unit!==field.unit||!/^[0-9a-f]{64}$/.test(item.definition_hash)||!['ACTIVE','UNCONFIGURED','SCHEDULED','DISABLED','EXPIRED','DEFINITION_CHANGED','INVALID_VALUE','INCOMPATIBLE_MODEL'].includes(item.status)||item.expires_at!==null&&utcInstantNanos(item.expires_at)===null)throw invalid()
  const missing=['UNCONFIGURED','SCHEDULED'].includes(item.status)
  if(missing?(item.publication_id!==null||item.publication_version!==null||item.expires_at!==null):(!uuid(item.publication_id)||!Number.isSafeInteger(item.publication_version)||Number(item.publication_version)<1))throw invalid()
  if(item.status==='ACTIVE'){
   if(!decimal(item.value)||!decimal(item.input_value)||item.expires_at!==null&&utcInstantNanos(item.expires_at)!<=utcInstantNanos(p.checked_at)!)throw invalid()
   const x=rational(item.value)
   if(field.key==='capacity.headroom'){const y=rational(item.input_value);if(x.n<x.d||x.n>2n*x.d||(x.n-x.d)*y.d!==y.n*x.d)throw invalid()}
   else if(!same(item.value,item.input_value))throw invalid()
   if(field.key==='capacity.trend_ratio'?(x.n>x.d):x.n<=0n)throw invalid()
   if(field.key==='monitor.sample_interval_sec'&&!['30','60','120','300'].includes(item.value))throw invalid()
   if(field.key.startsWith('retention.')&&(!/^[0-9]+$/.test(item.input_value)||BigInt(item.input_value)>2147483647n))throw invalid()
   if(field.key==='capacity.proxy_outage_sec'&&!/^[0-9]+$/.test(item.input_value))throw invalid()
  }else if(item.input_value!==null||(item.status==='INCOMPATIBLE_MODEL'?typeof item.value!=='string':item.value!==null))throw invalid()
 })
 return structuredClone(p)
}
/** The explicit import replaces every mapped field, including blanks, so disabled values cannot linger. */
export function capacityImportedValues(snapshot:CapacityRuntimeInputs){
 const inputs:Record<string,string>={},buffer:Record<string,string>={}
 snapshot.items.forEach((item,i)=>{const f=capacityRuntimeFields[i]!,v=item.status==='ACTIVE'?item.input_value??'':'';if(f.target==='outage_seconds')buffer.outage_seconds=v;else inputs[f.target]=v;if(f.target==='bytes_per_value')buffer.bytes_per_value=v})
 return {inputs,buffer}
}
