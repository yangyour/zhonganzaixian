import type {ProjectUser} from '../../src/utils/projectIdentity'
import type {MemberListTarget} from '../../src/utils/identityUserRead'
export const id=(n:number)=>'67000000-0000-4000-8000-'+n.toString(16).padStart(12,'0')
export const tenant=id(1)
export const user=(n=3,version=1):ProjectUser=>({id:id(n),display_name:'Synthetic user '+n,username:'synthetic'+n,active:true,local_login_enabled:true,must_change_password:null,locked_until:null,last_login_at:null,version,base_role:'viewer',member_active:true,permission_version:version,role_id:id(8),role_name:'Synthetic viewer',permissions:[]})
export const list=(items=[user(),user(4)],page=1,more=false)=>({tenant_id:tenant,items,page,page_size:50,has_more:more})
export const target=(page=1):MemberListTarget=>({kind:'LIST',query:'',includeInactive:true,page,user:null})
export const role=(n=8,owner=tenant)=>({id:id(n),tenant_id:owner,version:1,name:'Synthetic role',description:null,base_role:'viewer',permissions:[],builtin:false,enabled:true,assigned_members:0,created_at:'2026-10-21T00:00:00Z',updated_at:'2026-10-21T00:00:00Z'})
export const roles=(owner=tenant)=>({tenant_id:owner,items:[role(8,owner)],permission_catalog:{entries:[],dimensions:[]}})
export const member=(owner=tenant)=>({tenant_id:owner,tenant_name:'Synthetic tenant',base_role:'viewer',active:true,permission_version:1,role_id:id(8)})
export const binding=()=>({id:id(7),provider:'oidc',issuer:'https://synthetic.example.test',subject:'synthetic-subject',active:true,version:1,created_at:'2026-10-21T00:00:00Z'})
