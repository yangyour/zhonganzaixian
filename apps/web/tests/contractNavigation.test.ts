// @vitest-environment jsdom
import { expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { router } from '../src/router'
import { contractNavigationModules, navigationEntryName, navigationModuleTitle, visibleNavigationModules } from '../src/utils/navigationModules'
import { routeAllowed } from '../src/utils/routeAccess'
import type { Session } from '../src/api/types'

it('matches the ten module titles and order in the actual contract appendix', () => {
  const source = JSON.parse(readFileSync(resolve('../../doc/development/evidence/contract_requirements.json'), 'utf8'))
  const headings = [...new Set(source.requirements.map((entry: {module:string}) => entry.module))]
  expect(contractNavigationModules.filter(module => module.id !== 'settings').map(module => module.id + module.title)).toEqual(headings)
})
it('maps every live business route exactly once, retaining detail context and all original gates', () => {
  const mapped = contractNavigationModules.flatMap(module => module.items.map(item => item.name))
  const routes = router.getRoutes().filter(route => typeof route.meta.capability === 'string' && navigationEntryName(route.name) === route.name)
  expect(new Set(mapped).size).toBe(mapped.length)
  expect([...mapped].sort()).toEqual(routes.map(route => String(route.name)).sort())
  expect(navigationModuleTitle('contact-import-preview')).toBe('租户与权限管理')
  expect(navigationModuleTitle('actionable-tickets')).toBe('首页门户')
  const current = { user: {role:'viewer'}, capabilities:['assets:read'] } as Session
  expect(visibleNavigationModules(current, router.getRoutes()).map(module => module.id)).toEqual(['M3','M4'])
  expect(visibleNavigationModules({ ...current, capabilities: [] }, router.getRoutes())).toEqual([])
  expect(visibleNavigationModules(current, router.getRoutes()).some(module => module.id === 'M7')).toBe(false)
})

it('keeps the actionable drilldown behind both dashboard and ticket grants without duplicating the menu', () => {
  const route = router.getRoutes().find(route => route.name === 'actionable-tickets')!
  expect(route.path).toBe('/dashboard/actionable-tickets')
  for (const capabilities of [[], ['dashboard:read'], ['tickets:read']]) {
    expect(routeAllowed(route.meta, { user: { role: 'viewer' }, capabilities } as Session)).toBe(false)
  }
  expect(routeAllowed(route.meta, { user: { role: 'viewer' }, capabilities: ['dashboard:read', 'tickets:read'] } as Session)).toBe(true)
  expect(contractNavigationModules.flatMap(module => module.items).some(item => item.name === 'actionable-tickets')).toBe(false)
})

it('places service tiers and SLA compensation under their explicit contract clauses', () => {
  const source = JSON.parse(readFileSync(resolve('../../doc/development/evidence/contract_requirements.json'), 'utf8'))
  for (const [route, clause] of [['service-tiers', 'M2-10'], ['sla-reports', 'M5-9'], ['sla-reports', 'M5-10'], ['sla-reports', 'M5-19']]) {
    const requirement = source.requirements.find((entry: {id:string}) => entry.id === clause)
    const module = contractNavigationModules.find(module => module.items.some(item => item.name === route))
    expect(module!.id + module!.title).toBe(requirement.module)
  }
})
