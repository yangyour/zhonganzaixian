import { ref, shallowRef } from 'vue'
import { writeFailureUncertain } from './writeFailure'
/** Shared lifecycle only. Each business caller must strictly validate both commands and acknowledgments. */
export function createDurableCommand<T>(options:{
 scope:()=>string|null;prefix:string;storage:Pick<Storage,'getItem'|'setItem'|'removeItem'>
 validate:(raw:unknown)=>T;send:(command:T,signal:AbortSignal)=>Promise<unknown>;acknowledge:(raw:unknown,command:T)=>void
}){
 const pending=shallowRef<T|null>(null),busy=ref(false),blocked=ref(false),error=shallowRef<unknown>(null)
 let owner=options.scope(),epoch=0,controller:AbortController|null=null
 const key=()=>options.prefix+owner
 const copy=(value:T)=>options.validate(JSON.parse(JSON.stringify(value)))
 function dispose(){epoch++;controller?.abort();controller=null;pending.value=null;busy.value=false;error.value=null}
 function reset(){dispose();owner=options.scope();blocked.value=false;if(owner===null)return
  try{const raw=options.storage.getItem(key());if(raw){if(raw.length>65536)throw new Error('原请求超过恢复上限');pending.value=options.validate(JSON.parse(raw))}}catch(e){blocked.value=true;error.value=e}}
 function clear(){options.storage.removeItem(key());pending.value=null}
 async function perform(retry:boolean){if(owner===null||owner!==options.scope()||!pending.value||busy.value||blocked.value)return false
  const turn=++epoch,abort=new AbortController();controller=abort;busy.value=true;error.value=null
  try{const command=copy(pending.value);const result=await options.send(command,abort.signal)
   if(turn!==epoch||abort.signal.aborted||owner!==options.scope())return false
   options.acknowledge(result,command);clear();return true
  }catch(e){if(turn===epoch){error.value=e;if(!writeFailureUncertain(retry,e)){try{clear()}catch(storageFailure){blocked.value=true;error.value=storageFailure}}}return false}
  finally{if(turn===epoch){busy.value=false;controller=null}}
 }
 async function submit(raw:unknown){if(owner===null||owner!==options.scope()||pending.value||busy.value||blocked.value)return false
  try{const command=options.validate(raw),encoded=JSON.stringify(command);if(encoded.length>65536)throw new Error('原请求超过恢复上限');options.storage.setItem(key(),encoded);pending.value=copy(command)}catch(e){error.value=e;return false}return perform(false)}
 reset();return{pending,busy,blocked,error,submit,retry:()=>perform(true),cancel:()=>controller?.abort(),reset,dispose}
}
