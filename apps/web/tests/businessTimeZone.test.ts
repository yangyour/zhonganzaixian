import {afterEach,it,expect,vi} from 'vitest'
import {readFileSync} from 'node:fs'
import {businessTimeZone,displayTime} from '../src/utils/display'
afterEach(()=>vi.restoreAllMocks())
it('uses the contract-fixed platform timezone without inventing a new pending customer decision',()=>{const registry=JSON.parse(readFileSync('../../contracts/config-baseline.json','utf8'));const fixed=registry.settings.find((s:{key:string})=>s.key==='business.timezone');expect(fixed.decision_status).toBe('contract_fixed');expect(fixed.contract_value_kind).toBe('fixed_constraint');expect(fixed.question_ids).toEqual([]);expect(businessTimeZone).toBe(fixed.value)})
it('date display explicitly uses the fixed business timezone, not the browser local zone',()=>{const spy=vi.spyOn(Date.prototype,'toLocaleString').mockReturnValue('rendered');expect(displayTime('2026-10-04T16:00:00Z')).toBe('rendered');expect(spy).toHaveBeenCalledWith('zh-CN',{timeZone:businessTimeZone,hour12:false});expect(displayTime(null)).toBe('—')})
