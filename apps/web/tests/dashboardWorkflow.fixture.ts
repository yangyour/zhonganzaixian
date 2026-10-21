import type {DashboardWorkflowContext} from '../src/utils/dashboardWorkflows'
import type {TicketWorkflow,WorkflowState} from '../src/utils/ticketWorkflow'
export const id=(n:number)=>`78000000-0000-4000-8000-${n.toString(16).padStart(12,'0')}`
export const workflowContext:DashboardWorkflowContext={tenantId:id(1),actorId:id(2),permissionVersion:7,role:'padmin'}
export function workflow(n=20,state:WorkflowState='OPEN',customer=false):TicketWorkflow{
 const accepted=['ACCEPTED','IN_PROGRESS','RESOLVED','CLOSED'].includes(state),resolved=['RESOLVED','CLOSED'].includes(state)
 return {ticket_id:id(n),tenant_id:id(1),title:'Synthetic workflow '+n,receipt_no:'SYN-'+n,registration_created_at:'2026-10-21T00:00:00Z',version:state==='OPEN'?1:5,state,priority:'P2',service_tier:'STANDARD',assignee_id:accepted&&!customer?id(2):null,collaborator_ids:[],accepted_at:accepted?'2026-10-21T00:01:00Z':null,resolved_at:resolved?'2026-10-21T00:02:00Z':null,closed_at:state==='CLOSED'?'2026-10-21T00:03:00Z':null,confirm_due_at:resolved?'2026-10-23T00:02:00Z':null,close_mode:state==='CLOSED'?'customer_confirm':null,reopen_count:0,confirmation_cycle:resolved?1:0,resolution_note:resolved?'Synthetic resolution':null,response_due_at:'2026-10-21T00:30:00Z',recovery_due_at:'2026-10-21T04:00:00Z',sla_missing:{},sla_references:[],snapshot_hash:'a'.repeat(64),updated_at:'2026-10-21T00:03:00Z',observed_at:'2026-10-21T00:04:00Z',authority_source:'MOP',source:'postgresql',scope:customer?'REPORTER_ONLY':'CURRENT_TENANT_INTERNAL',customer_confirmation_status:'UNVERIFIED',production_approved:false,external_notification_delivery:'NOT_CONFIRMED'}
}
export const workflowPage=(items=[workflow()],more=false)=>({items,has_more:more,next_cursor:more?'synthetic.cursor':null,source:'postgresql'})
