import {readFileSync} from 'node:fs'
import assert from 'node:assert/strict'
import {readRegisteredTicket} from '/tmp/za-integration-work/apps/web/src/utils/ticketReader'
import {maskedContact,ticketRegistrationOrigin} from '/tmp/za-integration-work/apps/web/src/utils/ticketDetailPresentation'
const input=JSON.parse(readFileSync(process.argv[2]!,'utf8'))
for(const item of input.cases){
 const value=readRegisteredTicket(item.data,input.context,item.data.id)
 assert.equal(value.history[0]!.action,item.expected_action)
 if(item.expected_unknown_contact){assert.equal(value.contact_phone,'');assert.equal(maskedContact(value.contact_phone),'未提供');assert.match(ticketRegistrationOrigin(value),/监控/)}
 else assert.ok(value.contact_phone.length>0)
}
console.log(JSON.stringify({result:'PASS',cases:input.cases.length,scope:'PRODUCTION_TS_READERS_WITH_ACTUAL_HTTP_RESPONSES',browser:false}))
