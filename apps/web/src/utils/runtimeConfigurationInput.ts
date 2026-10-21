import {utcInstantNanos} from './utcInstant'
/** Reject impossible dates before Date can normalize them into another day. */
export function parseRuntimeUtc(text:string,optional=false):string|null{
 if(text===''&&optional)return null
 if(!/^(?!0000)\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(text)||utcInstantNanos(text)===null)throw Error('请填写真实有效的 UTC 日期时间（秒，可含1至3位毫秒）；不自动修改日期或补当前时间')
 return new Date(text).toISOString()
}
