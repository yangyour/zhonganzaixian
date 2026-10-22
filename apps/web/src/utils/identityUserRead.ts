import {ApiError} from '../api/client'
import {baseRoleLabels,readUserPage,readRolePage,type ProjectUser,type IdentityMembership,type IdentityBinding} from './projectIdentity'
import {worklogInstant} from './worklogWorkspace'
export interface MemberListTarget{kind:'LIST';query:string;includeInactive:boolean;page:number;user:ProjectUser|null}
export interface MemberDetailTarget{kind:'DETAIL';user:ProjectUser}
export type MemberReadTarget=MemberListTarget|MemberDetailTarget
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const version=(v:unknown)=>Number.isSafeInteger(v)&&Number(v)>0
const text=(v:unknown,max:number)=>typeof v==='string'&&v.length>0&&Array.from(v).length<=max&&!/[\u0000-\u001f\u007f-\u009f]/.test(v)
export const invalidMemberRead=()=>new ApiError(200,'INVALID_MEMBER_READ','成员分页、原账号或关联权限读取不一致，请恢复原读取后再编辑','—')
export function readMemberUser(raw:unknown,tenant:string,expected?:ProjectUser):ProjectUser{
 const user=readUserPage({tenant_id:tenant,items:[raw],page:1,page_size:50,has_more:false},tenant).items[0]!
 if(expected&&(user.id!==expected.id||user.version<expected.version||user.permission_version<expected.permission_version))throw invalidMemberRead()
 return user
}
export function readMemberList(raw:unknown,tenant:string,target:MemberListTarget,known:ProjectUser|null=null){
 const result=readUserPage(raw,tenant)
 if(result.page!==target.page||result.has_more&&result.items.length!==50||new Set(result.items.map(u=>u.id)).size!==result.items.length||!target.includeInactive&&result.items.some(u=>!u.active||!u.member_active))throw invalidMemberRead()
 if(known){const row=result.items.find(u=>u.id===known.id);if(row)readMemberUser(row,tenant,known)}
 return result
}
export function readMemberRoles(raw:unknown,tenant:string){const result=readRolePage(raw,tenant);if(new Set(result.items.map(r=>r.id)).size!==result.items.length)throw invalidMemberRead();return result}
export function readMemberRelations(members:unknown,bindings:unknown,allowedTenants:Iterable<string>):{memberships:IdentityMembership[];bindings:IdentityBinding[]}{
 if(!exact(members,['items'])||!Array.isArray(members.items)||!exact(bindings,['items'])||!Array.isArray(bindings.items))throw invalidMemberRead()
 const allowed=new Set(allowedTenants),tenants=new Set<string>(),ids=new Set<string>(),identities=new Set<string>()
 for(const m of members.items){if(!exact(m,['tenant_id','tenant_name','base_role','active','permission_version','role_id'])||typeof m.tenant_id!=='string'||!uuid.test(m.tenant_id)||!allowed.has(m.tenant_id)||tenants.has(m.tenant_id)||!text(m.tenant_name,200)||!Object.hasOwn(baseRoleLabels,String(m.base_role))||typeof m.active!=='boolean'||!version(m.permission_version)||m.role_id!==null&&!uuid.test(String(m.role_id)))throw invalidMemberRead();tenants.add(m.tenant_id)}
 for(const b of bindings.items){if(!exact(b,['id','provider','issuer','subject','active','version','created_at'])||typeof b.id!=='string'||!uuid.test(b.id)||ids.has(b.id)||b.provider!=='oidc'||!text(b.issuer,2000)||!/^https?:\/\/\S+$/.test(String(b.issuer))||!text(b.subject,255)||typeof b.active!=='boolean'||!version(b.version)||typeof b.created_at!=='string'||!b.created_at.endsWith('Z'))throw invalidMemberRead();try{worklogInstant(b.created_at)}catch{throw invalidMemberRead()}const identity=JSON.stringify([b.issuer,b.subject]);if(identities.has(identity))throw invalidMemberRead();identities.add(identity);ids.add(b.id)}
 return JSON.parse(JSON.stringify({memberships:members.items,bindings:bindings.items})) as {memberships:IdentityMembership[];bindings:IdentityBinding[]}
}
