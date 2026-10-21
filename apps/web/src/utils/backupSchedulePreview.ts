import {ApiError} from '../api/client'
import type {BackupPlanDetail} from './backupPlanDrafts'
import {utcInstantNanos} from './utcInstant'
export function validPreviewDate(value:string){
 if(!/^(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value))return false
 const d=new Date(value+'T12:00:00Z');return Number.isFinite(d.getTime())&&d.toISOString().slice(0,10)===value
}
const keys=['plan_id','tenant_id','version','archived','requested_date','daily_local_time','timezone','requested_local_date_time','candidate_instant','resolution','requested_enabled','enabled','effective','creates_run','execution_authorized','next_execution_at','missed_run_policy','scope','source','projection']
export interface BackupSchedulePreview {requested_local_date_time:string;candidate_instant:string|null;resolution:'UNPUBLISHED_CALENDAR_CANDIDATE'|'LOCAL_TIME_GAP'|'LOCAL_TIME_OVERLAP'}
export function readBackupSchedulePreview(raw:unknown,plan:BackupPlanDetail,date:string):BackupSchedulePreview{
 const v=raw as Record<string,unknown>
 const bad=()=>new ApiError(0,'INVALID_BACKUP_SCHEDULE_PREVIEW','日期预览范围、版本或未执行标记未通过核对','—')
 if(!validPreviewDate(date)||!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).length!==keys.length||!keys.every(k=>Object.hasOwn(v,k)))throw bad()
 if(v.plan_id!==plan.id||v.tenant_id!==plan.tenant_id||v.version!==plan.version||v.archived!==plan.archived||v.requested_enabled!==plan.requested_enabled||v.requested_date!==date||v.daily_local_time!==plan.daily_local_time||v.timezone!=='Asia/Shanghai'||v.requested_local_date_time!==date+'T'+plan.daily_local_time||v.scope!=='CURRENT_TENANT_PADMIN'||v.source!=='postgresql'||v.projection!=='UNPUBLISHED_CALENDAR_PREVIEW'||['enabled','effective','creates_run','execution_authorized'].some(k=>v[k]!==false)||v.next_execution_at!==null||v.missed_run_policy!==null)throw bad()
 if(v.resolution==='UNPUBLISHED_CALENDAR_CANDIDATE'?utcInstantNanos(v.candidate_instant)===null:!['LOCAL_TIME_GAP','LOCAL_TIME_OVERLAP'].includes(String(v.resolution))||v.candidate_instant!==null)throw bad()
 return {requested_local_date_time:v.requested_local_date_time as string,candidate_instant:v.candidate_instant as string|null,resolution:v.resolution as BackupSchedulePreview['resolution']}
}
