// @vitest-environment jsdom
import { expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { router } from '../src/router'
import { contractNavigationModules, navigationModuleTitle, visibleNavigationModules } from '../src/utils/navigationModules'
import type { Session } from '../src/api/types'

it('matches the ten module titles and order in the actual contract appendix', () => {
  const source = JSON.parse(readFileSync(resolve('../../doc/development/evidence/contract_requirements.json'), 'utf8'))
  const headings = [...new Set(source.requirements.map((entry: {module:string}) => entry.module))]
  expect(contractNavigationModules.filter(module => module.id !== 'settings').map(module => module.id + module.title)).toEqual(headings)
})
it('maps every live business route exactly once, retaining detail context and all original gates', () => {
  const mapped = contractNavigationModules.flatMap(module => module.items.map(item => item.name))
  const routes = router.getRoutes().filter(route => typeof route.meta.capability === 'string' && route.name !== 'contact-import-preview')
  expect(new Set(mapped).size).toBe(mapped.length)
  expect([...mapped].sort()).toEqual(routes.map(route => String(route.name)).sort())
  expect(navigationModuleTitle('contact-import-preview')).toBe('租户与权限管理')
  const current = { user: {role:'viewer'}, capabilities:['assets:read'] } as Session
  expect(visibleNavigationModules(current, router.getRoutes()).map(module => module.id)).toEqual(['M3','M4'])
  expect(visibleNavigationModules({ ...current, capabilities: [] }, router.getRoutes())).toEqual([])
  expect(visibleNavigationModules(current, router.getRoutes()).some(module => module.id === 'M7')).toBe(false)
})
