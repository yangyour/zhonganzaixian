import {ApiError} from '../api/client'
import {validateDependencyEdge,type ProposedDependency} from './assetDependencyWorkspace'
export interface TopologyNode {id:string;name:string;type:string;management_ip:string;site_id:string}
export interface CustomerTopology {tenant_id:string;version:number;updated_at:string|null;source:'postgresql';scope:'CURRENT_TENANT';lifecycle_status:'DRAFT';physical_topology_verified:false;monitoring_applied:false;focus_asset_id:string|null;offset:number;page_size:50;total_edges:number;has_more:boolean;nodes:TopologyNode[];edges:ProposedDependency[]}
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>object(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const fail=()=>new ApiError(200,'INVALID_TOPOLOGY_RESPONSE','拓扑响应与客户或分页范围不符，已清除显示','—')
export function readCustomerTopology(raw:unknown,tenant:string,offset:number,focus:string|null,version?:number):CustomerTopology {
 if(!exact(raw,['tenant_id','version','updated_at','source','scope','lifecycle_status','physical_topology_verified','monitoring_applied','focus_asset_id','offset','page_size','total_edges','has_more','nodes','edges'])||raw.tenant_id!==tenant||raw.offset!==offset||raw.focus_asset_id!==focus||raw.page_size!==50||raw.source!=='postgresql'||raw.scope!=='CURRENT_TENANT'||raw.lifecycle_status!=='DRAFT'||raw.physical_topology_verified!==false||raw.monitoring_applied!==false||!Number.isSafeInteger(raw.version)||Number(raw.version)<0||version!==undefined&&raw.version!==version||!Number.isSafeInteger(raw.total_edges)||Number(raw.total_edges)<offset||Number(raw.total_edges)>10000||!Array.isArray(raw.nodes)||raw.nodes.length>101||!Array.isArray(raw.edges)||raw.edges.length!==Math.min(50,Number(raw.total_edges)-offset)||raw.has_more!==(offset+raw.edges.length<Number(raw.total_edges)))throw fail()
 if(raw.version===0 ? raw.updated_at!==null||raw.total_edges!==0 : typeof raw.updated_at!=='string'||!Number.isFinite(Date.parse(raw.updated_at)))throw fail()
 const ids=new Set<string>()
 for(const n of raw.nodes){if(!exact(n,['id','name','type','management_ip','site_id'])||typeof n.id!=='string'||!uuid.test(n.id)||ids.has(n.id)||!['name','type','management_ip','site_id'].every(k=>typeof n[k]==='string'&&String(n[k]).length>0))throw fail();ids.add(n.id)}
 const used=new Set<string>(focus?[focus]:[]),pairs=new Set<string>()
 for(const e of raw.edges){validateDependencyEdge(e);const key=e.parent_id+'/'+e.child_id;if(pairs.has(key)||!ids.has(e.parent_id)||!ids.has(e.child_id)||focus!==null&&e.parent_id!==focus&&e.child_id!==focus)throw fail();pairs.add(key);used.add(e.parent_id);used.add(e.child_id)}
 if(used.size!==ids.size||[...used].some(id=>!ids.has(id)))throw fail()
 return structuredClone(raw) as unknown as CustomerTopology
}
/** A bounded page view, not a complete physical network or inferred root-cause map. */
export function topologyPositions(nodes:TopologyNode[],edges:ProposedDependency[]){
 const level=new Map(nodes.map(n=>[n.id,0])),degree=new Map(nodes.map(n=>[n.id,0])),children=new Map<string,string[]>()
 for(const e of edges){degree.set(e.child_id,(degree.get(e.child_id)||0)+1);children.set(e.parent_id,[...(children.get(e.parent_id)||[]),e.child_id])}
 const queue=nodes.filter(n=>degree.get(n.id)===0).map(n=>n.id)
 for(let i=0;i<queue.length;i++)for(const child of children.get(queue[i]!)||[]){level.set(child,Math.max(level.get(child)||0,(level.get(queue[i]!)||0)+1));degree.set(child,degree.get(child)!-1);if(degree.get(child)===0)queue.push(child)}
 const rows=new Map<number,number>();return nodes.map(n=>{const col=level.get(n.id)||0,row=rows.get(col)||0;rows.set(col,row+1);return {...n,x:20+col*240,y:20+row*85}})
}
