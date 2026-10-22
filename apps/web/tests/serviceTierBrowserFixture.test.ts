import {it,expect} from 'vitest'
import {tierPage,inspection,tenant,actor,ticket} from '../qa/service-tiers.data'
import {readServiceTierPage,readTicketSlaInspection} from '../src/utils/serviceTiers'
const scope={tenantId:tenant,actorId:actor,permissionVersion:1,canWrite:true}
it('browser service tier fixture passes the production page reader',()=>{expect(readServiceTierPage(tierPage(),scope).current?.monthly_fee_fen).toBe(100)})
it('browser SLA diagnostic fixture passes the production reader with all 60 task and source rows',()=>{const result=readTicketSlaInspection(inspection(),scope,ticket);expect(result.clock_tasks).toHaveLength(60);expect(result.sla_references).toHaveLength(60);expect(result.response_clock?.breached).toBeNull();expect(result.recovery_clock?.status).toBe('PENDING_CONFIG')})
