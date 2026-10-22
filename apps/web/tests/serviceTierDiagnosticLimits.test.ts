import {it,expect} from 'vitest'
import {readTicketSlaInspection,SLA_DIAGNOSTIC_LIMITS} from '../src/utils/serviceTiers'
import {inspection,ticket,tenant,actor} from '../qa/service-tiers.data'
import contract from '../../../contracts/local-api.openapi.json'
const scope={tenantId:tenant,actorId:actor,permissionVersion:1,canWrite:true}
it('diagnostic limits match the existing published API contract exactly',()=>{const schema=contract.components.schemas.TicketSlaInspection.properties;expect(SLA_DIAGNOSTIC_LIMITS.clockTasks).toBe(schema.clock_tasks.maxItems);expect(SLA_DIAGNOSTIC_LIMITS.policyReferences).toBe(schema.sla_references.maxItems)})
it('accepts the exact 64 task and 20 reference contract boundary',()=>{const result=readTicketSlaInspection(inspection(64),scope,ticket);expect(result.clock_tasks).toHaveLength(64);expect(result.sla_references).toHaveLength(20)})
it('rejects 65 tasks without presenting a truncated success',()=>{try{readTicketSlaInspection(inspection(65),scope,ticket);throw Error('expected rejection')}catch(error){expect(error).toMatchObject({status:413,code:'SLA_INSPECTION_LIMIT'})}})
it('rejects 21 frozen references rather than silently dropping the last one',()=>{const value=inspection();value.sla_references.push({...value.sla_references[0]!,key:'extra'});expect(()=>readTicketSlaInspection(value,scope,ticket)).toThrowError(/未截断展示/)})
