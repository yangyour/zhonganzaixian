import { afterEach, expect, it } from 'vitest'
import { maySwitchTenant, session } from '../src/stores/session'
import type { Session } from '../src/api/types'
afterEach(() => { session.value = null })
it.each(['viewer','tadmin','msp','padmin'] as const)('shows switching for %s only when explicitly assigned multiple tenants', role => {
  const value: Session = { user: {id:'synthetic',display_name:'合成',role},tenant:{id:'A',name:'A'},allowed_tenants:[{id:'A',name:'A'}],capabilities:[],permission_version:1,csrf_token:'isolated',mode:'remote_test',identity_source:'project_local' }
  session.value=value;expect(maySwitchTenant.value).toBe(false)
  session.value={...value,allowed_tenants:[...value.allowed_tenants,{id:'B',name:'B'}]};expect(maySwitchTenant.value).toBe(true)
  session.value=null;expect(maySwitchTenant.value).toBe(false)
})
