// @vitest-environment jsdom
import {it,expect} from 'vitest'
import {router} from '../src/router'
import {visibleNavigationModules} from '../src/utils/navigationModules'
import {routeAllowed} from '../src/utils/routeAccess'
import type {Session} from '../src/api/types'
const auth=(capabilities:string[],role='padmin')=>({user:{role},capabilities}) as Session
it('registered personal task route uses both grants and internal roles in navigation and direct access',()=>{const route=router.getRoutes().find(r=>r.name==='my-tasks')!;expect(route.path).toBe('/my-tasks');expect(route.meta.capability).toBe('tickets:read');expect(route.meta.capabilities).toEqual(['ticket_notes:write_internal']);for(const current of [auth(['tickets:read']),auth(['ticket_notes:write_internal']),auth(['tickets:read','ticket_notes:write_internal'],'viewer')]){expect(routeAllowed(route.meta,current)).toBe(false);expect(visibleNavigationModules(current,router.getRoutes()).flatMap(m=>m.items).some(i=>i.name==='my-tasks')).toBe(false)}expect(visibleNavigationModules(auth(['tickets:read','ticket_notes:write_internal']),router.getRoutes()).flatMap(m=>m.items).find(i=>i.name==='my-tasks')?.title).toBe('我的待办')})
