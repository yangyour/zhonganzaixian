import type {DeliveryScope,NotificationDelivery} from '../../src/utils/notificationDelivery'
export const id=(n:number)=>'65000000-0000-4000-8000-'+n.toString(16).padStart(12,'0')
export const deliveryScope=(channel:'email'|'wecom'):DeliveryScope=>({channel,tenantId:id(1),actorId:id(2),messageId:id(3)})
export function delivery(channel:'email'|'wecom',version=0):NotificationDelivery{
 return {id:id(4),tenant_id:id(1),actor_id:id(2),source_message_id:id(3),recipient:channel==='email'?'recipient@example.test':'synthetic-member',kind:'NOTIFICATION',status:version===0?'QUEUED':version===1?'PROCESSING':'ACCEPTED',version,error_code:null,created_at:'2026-10-21T00:00:00Z',started_at:version?'2026-10-21T00:00:01Z':null,finished_at:version===2?'2026-10-21T00:00:02Z':null,...(channel==='email'?{sender:'sender@example.test',message_id:version===2?'synthetic-message':null,smtp_code:version===2?250:null}:{provider_message_id:version===2?'synthetic-message':null,provider_code:version===2?0:null})} as NotificationDelivery
}
