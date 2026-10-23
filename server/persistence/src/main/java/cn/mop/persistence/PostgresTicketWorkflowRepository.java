package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.sla.TicketSlaClockEvaluation;
import cn.mop.domain.sla.TicketSlaDiagnosticLimits;
import cn.mop.domain.ticket.*;
import cn.mop.domain.ticket.WorkflowLifecycle.*;
import cn.mop.domain.ticket.TicketWorkflowRepository.*;
import com.fasterxml.jackson.databind.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import static cn.mop.persistence.PostgresRuntimeConfigurationRepository.map;

/** Append-only workflow, clock tasks and outbox. Never mutates the historical registration receipt. */
public final class PostgresTicketWorkflowRepository implements TicketWorkflowRepository {
    private final DataSource source;
    private final PostgresPlatformRepository platform;
    private final ObjectMapper mapper;
    private record Parent(String id,String reporter,String title,String receipt,Instant created) {}
    private record Event(String id,Snapshot snapshot,String hash) {}
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    public PostgresTicketWorkflowRepository(DataSource source,PostgresPlatformRepository platform,ObjectMapper mapper){
        this.source=source;this.platform=platform;this.mapper=snapshotMapper(mapper);
    }
    // V33 immutable JSON/SQL guards use camelCase. HTTP naming preferences must never alter stored snapshots.
    static ObjectMapper snapshotMapper(ObjectMapper supplied){
        return supplied.copy().setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
            .findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    private <T>T read(Access a,Work<T> work){
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{member(c,a,"tickets:read",false);T result=work.run(c);c.commit();return result;}
            catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }catch(SQLException e){WorkflowAuthorityConflict.rejectIfConflict(e);throw unavailable();}
    }
    @Override public void authorizeCommand(Access a,String ticket,boolean customer){
        try{Connection c=platform.idempotentCommandConnection();String role=member(c,a,customer?"tickets:report":"ticket_notes:write_internal",true);parent(c,a,ticket,role,true);}
        catch(SQLException e){WorkflowAuthorityConflict.rejectIfConflict(e);throw unavailable();}
    }
    @Override public Map<String,Object> read(Access a,String ticket){return read(a,c->{String role=member(c,a,"tickets:read",false);Parent p=parent(c,a,ticket,role,false);return view(a,p,latest(c,a.tenantId(),ticket),role);});}
    @Override public Map<String,Object> readSla(Access a,String ticket,CustomerConfirmationWindow.Tier currentTenantTier){
        return read(a,c->{
            String role=member(c,a,"tickets:read",false);Parent p=parent(c,a,ticket,role,false);boolean customer=Capabilities.customer(role);
            Instant observed=now(c);Event event=latest(c,a.tenantId(),ticket);
            if(event==null)return map("ticket_id",p.id(),"tenant_id",a.tenantId(),"receipt_no",p.receipt(),"registration_created_at",p.created().toString(),
                "workflow_version",0L,"state",null,"priority",null,"frozen_service_tier",null,"current_tenant_service_tier",currentTenantTier==null?null:currentTenantTier.name(),
                "tier_changed_since_open",false,"response_clock",null,"recovery_clock",null,"clock_tasks",List.of(),"sla_missing",Map.of(),"sla_references",List.of(),
                "reopen_count",0,"reopen_policy_status","NOT_REOPENED","metric_version",TicketSlaClockEvaluation.METRIC_VERSION,"observed_at",observed.toString(),"source","postgresql");
            var recovery=monitoringRecovery(c,a.tenantId(),ticket);
            var eval=TicketSlaClockEvaluation.evaluate(event.snapshot(),currentTenantTier,observed,recovery.linked(),recovery.recoveredAt());
            if(!customer)TicketSlaDiagnosticLimits.requireWithinLimits(0,eval.slaReferences().size());
            var tasks=new ArrayList<Map<String,Object>>();
            if(!customer)try(var s=statement(c,"SELECT j.clock_key,j.kind,j.due_at,r.outcome,r.completed_at FROM public.mop_ticket_workflow_clock j LEFT JOIN public.mop_ticket_workflow_clock_receipt r ON r.task_id=j.id AND r.tenant_id=j.tenant_id WHERE j.tenant_id=?::uuid AND j.ticket_id=?::uuid ORDER BY j.due_at,j.clock_key LIMIT "+TicketSlaDiagnosticLimits.CLOCK_TASK_LOOKAHEAD)){
                s.setString(1,a.tenantId());s.setString(2,ticket);try(var r=s.executeQuery()){while(r.next())tasks.add(map("clock_key",r.getString(1),"kind",r.getString(2),"due_at",time(r,"due_at").toString(),"outcome",r.getString(4),"completed_at",r.getObject(5)==null?null:time(r,"completed_at").toString()));}
            }
            TicketSlaDiagnosticLimits.requireWithinLimits(tasks.size(),customer?0:eval.slaReferences().size());
            return map("ticket_id",p.id(),"tenant_id",a.tenantId(),"receipt_no",p.receipt(),"registration_created_at",p.created().toString(),
                "workflow_version",event.snapshot().version(),"state",eval.workflowState().name(),"priority",eval.priority(),
                "frozen_service_tier",eval.frozenServiceTier().name(),"current_tenant_service_tier",eval.currentTenantServiceTier()==null?null:eval.currentTenantServiceTier().name(),
                "tier_changed_since_open",eval.tierChangedSinceOpen(),"response_clock",clockDto(eval.responseClock()),"recovery_clock",clockDto(eval.recoveryClock()),
                "clock_tasks",tasks,"sla_missing",eval.slaMissing(),"sla_references",customer?List.of():eval.slaReferences(),
                "reopen_count",eval.reopenCount(),"reopen_policy_status",eval.reopenPolicyStatus(),"metric_version",eval.metricVersion(),
                "observed_at",observed.toString(),"source","postgresql");
        });
    }
    private static Map<String,Object> clockDto(TicketSlaClockEvaluation.ClockStatus clock){
        return map("name",clock.name(),"due_at",str(clock.dueAt()),"completed_at",str(clock.completedAt()),"paused",clock.paused(),"breached",clock.breached(),
            "status",clock.status(),"missing_reason",clock.missingReason(),
            "thresholds",map("p50_at",str(clock.thresholds().p50At()),"p80_at",str(clock.thresholds().p80At()),"p100_at",str(clock.thresholds().p100At())),
            "working_windows_count",clock.workingWindows().size());
    }
    @Override public List<Map<String,Object>> list(Access a,Query q){
        if(q==null||q.limit()<1||q.limit()>101||(q.beforeCreatedAt()==null)!=(q.beforeId()==null))throw invalid();
        return read(a,c->{String role=member(c,a,"tickets:read",false);boolean customer=Capabilities.customer(role);var result=new ArrayList<Map<String,Object>>();
            String sql="SELECT t.id::text,t.reporter_id::text,t.title,t.receipt_no,t.created_at,e.id::text event_id,e.snapshot,e.snapshot_hash FROM public.mop_ticket t JOIN LATERAL (SELECT id,snapshot,snapshot_hash FROM public.mop_ticket_workflow_event WHERE tenant_id=t.tenant_id AND ticket_id=t.id ORDER BY version DESC LIMIT 1) e ON true WHERE t.tenant_id=?::uuid"
                +(customer?" AND t.reporter_id=?::uuid":"")+(q.state()!=null?" AND e.snapshot->>'state'=?":"")+(q.priority()!=null?" AND e.snapshot->'sla'->>'priority'=?":"")
                +(q.assigneeId()!=null?" AND e.snapshot->>'assigneeId'=?":"")+(q.beforeId()!=null?" AND (t.created_at,t.id)<(?,?::uuid)":"")+" ORDER BY t.created_at DESC,t.id DESC LIMIT ?";
            try(var s=statement(c,sql)){int n=1;s.setString(n++,a.tenantId());if(customer)s.setString(n++,a.actorId());if(q.state()!=null)s.setString(n++,q.state());if(q.priority()!=null)s.setString(n++,q.priority());if(q.assigneeId()!=null)s.setString(n++,q.assigneeId());if(q.beforeId()!=null){instant(s,n++,q.beforeCreatedAt());s.setString(n++,q.beforeId());}s.setInt(n,q.limit());
                try(var r=s.executeQuery()){while(r.next()){Parent p=new Parent(r.getString("id"),r.getString("reporter_id"),r.getString("title"),r.getString("receipt_no"),time(r,"created_at"));result.add(view(a,p,new Event(r.getString("event_id"),decode(r.getString("snapshot")),r.getString("snapshot_hash")),role));}}}
            return result;
        });
    }
    @Override public List<Map<String,Object>> events(Access a,String ticket,long before,int limit){
        if(before<1||limit<1||limit>101)throw invalid();
        return read(a,c->{String role=member(c,a,"tickets:read",false);parent(c,a,ticket,role,false);boolean customer=Capabilities.customer(role);var result=new ArrayList<Map<String,Object>>();
            try(var s=statement(c,"SELECT id::text,version,action,actor_id::text,details,created_at,snapshot_hash,snapshot->>'state' state FROM public.mop_ticket_workflow_event WHERE tenant_id=?::uuid AND ticket_id=?::uuid AND version<? ORDER BY version DESC LIMIT ?")){
                s.setString(1,a.tenantId());s.setString(2,ticket);s.setLong(3,before);s.setInt(4,limit);try(var r=s.executeQuery()){while(r.next()){
                    String action=r.getString("action");var details=json(r.getString("details"));
                    result.add(map("id",r.getString("id"),"version",r.getLong("version"),"action",customer&&Set.of("REASSIGN","ACCEPT","START").contains(action)?"PROCESSING_UPDATED":action,
                        "actor_id",customer?null:r.getString("actor_id"),"state",r.getString("state"),"note",customer&&Set.of("REASSIGN","OPEN","CANCEL").contains(action)?null:details.get("note"),
                        "details",customer?null:details,"created_at",time(r,"created_at").toString(),"snapshot_hash",r.getString("snapshot_hash")));
                }}}
            return result;
        });
    }
    @Override public List<Map<String,Object>> assignees(Access a){return read(a,c->{member(c,a,"ticket_notes:write_internal",false);var result=new ArrayList<Map<String,Object>>();
        try(var s=statement(c,"SELECT u.id::text,u.display_name,m.role_code FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.active AND u.active AND m.role_code IN ('msp','padmin') AND 'ticket_notes:write_internal'=ANY(public.mop_effective_capabilities(u.id,m.tenant_id)) ORDER BY u.display_name,u.id LIMIT 101")){s.setString(1,a.tenantId());try(var r=s.executeQuery()){while(r.next())result.add(map("id",r.getString(1),"display_name",r.getString(2),"role",r.getString(3)));}}
        if(result.size()>100)throw new DomainException(422,"ASSIGNEE_LIMIT","当前候选人超过100名，请先配置明确的服务队列");return result;
    });}
    @Override public Map<String,Object> activate(Access a,String ticket,long expected,String priority,CustomerConfirmationWindow.Tier tier,String reason,SlaProvider provider){
        Connection c=platform.idempotentCommandConnection();try{
            String role=member(c,a,"ticket_notes:write_internal",true);Parent p=parent(c,a,ticket,role,true);lock(c,a.tenantId(),ticket,false);
            if(expected!=0||latest(c,a.tenantId(),ticket)!=null)throw conflict();TicketCustomerActionRules.requireUnresolvedReason(reason);
            if(reason.codePointCount(0,reason.length())>3000)throw invalid();
            Snapshot snapshot=WorkflowLifecycle.open(provider.freeze(a,priority,tier,p.created()),now(c));
            Event event=append(c,a.tenantId(),ticket,a.actorId(),"OPEN",snapshot,map("note",reason,"tier_source","EXPLICIT_TEST_ASSIGNMENT","customer_approval","UNVERIFIED"),List.of("ticket.opened"));
            schedule(c,a.tenantId(),ticket,snapshot);platform.audit(a.actorId(),a.tenantId(),"ticket.workflow.open","ticket",ticket,"SUCCEEDED","LOCAL_AUTHORITY_TEST_WORKFLOW",a.traceId());return view(a,p,event,role);
        }catch(SQLException e){WorkflowAuthorityConflict.rejectIfConflict(e);throw unavailable();}
    }
    @Override public Map<String,Object> transition(Access a,String ticket,Command command){
        Connection c=platform.idempotentCommandConnection();try{
            boolean customer=Set.of(Action.CLOSE,Action.REOPEN).contains(command.action());String role=member(c,a,customer?"tickets:report":"ticket_notes:write_internal",true);
            Parent p=parent(c,a,ticket,role,true);lock(c,a.tenantId(),ticket,false);Event current=latest(c,a.tenantId(),ticket);if(current==null)throw new DomainException(422,"WORKFLOW_NOT_OPEN","请先明确优先级和服务档位建立正式工单");
            if(command.action()==Action.REASSIGN)assignee(c,a.tenantId(),command.assigneeId());
            if(command.action()==Action.CLOSE&&!linkedEventsReviewed(c,a.tenantId(),ticket))
                throw new DomainException(422,"LINKED_EVENTS_UNREVIEWED","关联监控事件尚未恢复并完成人工复核，不能关闭工单");
            Operator who=new Operator(a.actorId(),Capabilities.internal(role),role.equals("padmin"),customer&&Capabilities.customer(role)&&a.actorId().equals(p.reporter()));
            Transition change=WorkflowLifecycle.apply(current.snapshot(),command,who,now(c));
            Event event=append(c,a.tenantId(),ticket,a.actorId(),command.action().name(),change.snapshot(),map("note",command.note(),"reason_code",command.reasonCode(),"previous_assignee_id",current.snapshot().assigneeId(),"new_assignee_id",change.snapshot().assigneeId(),"retain_previous",command.retainPrevious()),change.effects());
            schedule(c,a.tenantId(),ticket,change.snapshot());platform.audit(a.actorId(),a.tenantId(),"ticket.workflow."+command.action().name().toLowerCase(Locale.ROOT),"ticket",ticket,"SUCCEEDED","WORKFLOW_EVENT_AND_OUTBOX_COMMITTED_TOGETHER",a.traceId());return view(a,p,event,role);
        }catch(SQLException e){WorkflowAuthorityConflict.rejectIfConflict(e);throw unavailable();}
    }
    private Event append(Connection c,String tenant,String ticket,String actor,String action,Snapshot snapshot,Map<String,Object> details,List<String> effects)throws SQLException{
        String id=UUID.randomUUID().toString(),snapshotJson=encode(snapshot),hash=digest(snapshotJson);
        try(var s=statement(c,"INSERT INTO public.mop_ticket_workflow_event(id,tenant_id,ticket_id,version,action,actor_id,snapshot,details,snapshot_hash,created_at) VALUES(?::uuid,?::uuid,?::uuid,?,?,?::uuid,?::jsonb,?::jsonb,?,?)")){
            s.setString(1,id);s.setString(2,tenant);s.setString(3,ticket);s.setLong(4,snapshot.version());s.setString(5,action);s.setString(6,actor);s.setString(7,snapshotJson);s.setString(8,encode(details));s.setString(9,hash);instant(s,10,snapshot.updatedAt());s.executeUpdate();
        }
        Event event=new Event(id,snapshot,hash);for(String type:effects)outbox(c,tenant,ticket,event,"event:"+snapshot.version()+":"+type,type,snapshot.updatedAt());return event;
    }
    private void outbox(Connection c,String tenant,String ticket,Event event,String key,String type,Instant at)throws SQLException{
        try(var s=statement(c,"INSERT INTO public.mop_ticket_workflow_outbox(id,tenant_id,ticket_id,event_id,workflow_version,effect_key,event_type,payload,created_at) VALUES(?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?::jsonb,?) ON CONFLICT(tenant_id,ticket_id,effect_key) DO NOTHING")){
            s.setString(1,UUID.randomUUID().toString());s.setString(2,tenant);s.setString(3,ticket);s.setString(4,event.id());s.setLong(5,event.snapshot().version());s.setString(6,key);s.setString(7,type);s.setString(8,encode(map("ticket_id",ticket,"workflow_version",event.snapshot().version(),"event_id",event.id(),"event_type",type,"snapshot_hash",event.hash())));instant(s,9,at);s.executeUpdate();}
    }
    private void schedule(Connection c,String tenant,String ticket,Snapshot snapshot)throws SQLException{
        if(snapshot.version()==1)for(String clock:List.of("response","recovery"))for(int percent:List.of(50,80,100)){
            Instant at=snapshot.sla().threshold(clock,percent);if(at!=null)task(c,tenant,ticket,clock+":"+percent,"SLA",at,0,snapshot.updatedAt());
        }
        if(snapshot.state()==State.RESOLVED&&snapshot.confirmDueAt()!=null){
            Instant reminder=snapshot.confirmDueAt().minus(Duration.ofHours(12));if(reminder.isBefore(snapshot.resolvedAt()))reminder=snapshot.resolvedAt();
            task(c,tenant,ticket,"confirmation:"+snapshot.confirmationCycle(),"CONFIRMATION_REMINDER",reminder,snapshot.confirmationCycle(),snapshot.updatedAt());
            task(c,tenant,ticket,"auto_close:"+snapshot.confirmationCycle(),"AUTO_CLOSE",snapshot.confirmDueAt(),snapshot.confirmationCycle(),snapshot.updatedAt());
        }
    }
    private static void task(Connection c,String tenant,String ticket,String key,String kind,Instant due,int cycle,Instant at)throws SQLException{
        try(var s=statement(c,"INSERT INTO public.mop_ticket_workflow_clock(id,tenant_id,ticket_id,clock_key,due_at,kind,cycle,created_at) VALUES(?::uuid,?::uuid,?::uuid,?,?,?,?,?) ON CONFLICT(tenant_id,ticket_id,clock_key) DO NOTHING")){
            s.setString(1,UUID.randomUUID().toString());s.setString(2,tenant);s.setString(3,ticket);s.setString(4,key);instant(s,5,due);s.setString(6,kind);s.setInt(7,cycle);instant(s,8,at);s.executeUpdate();}
    }

    /** Called inside the authorized event transaction. Only committed outbox facts are consumed by the relay. */
    void monitoringRecurrence(Connection c, String tenant, String ticket, String monitoringEvent,
                              int problemSequence, boolean notificationEligible, Instant at) throws SQLException {
        lock(c, tenant, ticket, false);
        Event current = latest(c, tenant, ticket);
        if (current == null) {
            return; // Registration has not been activated; no SLA or recipients are invented.
        }
        if (Set.of(State.CLOSED, State.CANCELLED).contains(current.snapshot().state())) {
            throw new DomainException(409, "TICKET_TERMINAL", "关联工单已结束，不能向该工单追加复发周期");
        }
        UUID eventId = UUID.fromString(monitoringEvent);
        String onset = monitoringEvent + ":" + problemSequence;
        if (notificationEligible) {
            outbox(c, tenant, ticket, current, "monitoring:" + onset,
                "ticket.monitoring_recurred", at);
        }
        for (int percent : List.of(50, 80, 100)) {
            Instant due = current.snapshot().sla().threshold("recovery", percent);
            if (due != null) {
                task(c, tenant, ticket, new MonitoringRecurrenceClock(percent, eventId, problemSequence).key(),
                    "SLA", due, 0, at);
            }
        }
    }

    /** Existing terminal workflows cannot receive another problem by reusing a recovered event. */
    boolean terminal(Connection c, String tenant, String ticket) throws SQLException {
        Event current = latest(c, tenant, ticket);
        return current != null && Set.of(State.CLOSED, State.CANCELLED).contains(current.snapshot().state());
    }

    private static boolean currentMonitoringCycle(Connection c, String tenant, String ticket,
                                                  MonitoringRecurrenceClock cycle) throws SQLException {
        String sql = "SELECT event_id::text,sequence_no FROM ("
            + "SELECT m.event_id,m.sequence_no,m.source_phase,m.occurred_at,m.recorded_at,"
            + "lag(m.source_phase) OVER (PARTITION BY m.event_id ORDER BY m.sequence_no) AS previous_phase "
            + "FROM public.mop_monitoring_event_member m JOIN public.mop_monitoring_event e "
            + "ON e.tenant_id=m.tenant_id AND e.id=m.event_id WHERE e.tenant_id=?::uuid AND e.ticket_id=?::uuid"
            + ") signals WHERE source_phase='PROBLEM' AND (previous_phase IS NULL OR previous_phase='RECOVERED') "
            + "ORDER BY occurred_at DESC,recorded_at DESC,event_id DESC,sequence_no DESC LIMIT 1";
        try (var statement = statement(c, sql)) {
            statement.setString(1, tenant);
            statement.setString(2, ticket);
            try (var rows = statement.executeQuery()) {
                return rows.next() && cycle.eventId().toString().equals(rows.getString(1))
                    && cycle.sequence() == rows.getInt(2);
            }
        }
    }

    private static boolean hasMonitoringRecurrence(Connection c, String tenant, String ticket) throws SQLException {
        try (var statement = statement(c, "SELECT EXISTS(SELECT 1 FROM public.mop_ticket_workflow_clock "
                + "WHERE tenant_id=?::uuid AND ticket_id=?::uuid AND clock_key LIKE 'recovery:%:monitor:%')")) {
            statement.setString(1, tenant);
            statement.setString(2, ticket);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }
    @Override public int tick(String tenant,int limit){
        uuid(tenant);if(limit<1||limit>100)throw invalid();
        return platform.ticketClockTransaction(()->{Connection c=platform.ticketClockConnection();try{
            record Due(String id,String ticket,String key,String kind,int cycle){}var jobs=new ArrayList<Due>();
            try(var s=statement(c,"SELECT j.id::text,j.ticket_id::text,j.clock_key,j.kind,j.cycle FROM public.mop_ticket_workflow_clock j WHERE j.tenant_id=?::uuid AND j.due_at<=clock_timestamp() AND NOT EXISTS(SELECT 1 FROM public.mop_ticket_workflow_clock_receipt r WHERE r.task_id=j.id) ORDER BY j.due_at,j.id LIMIT ?")){s.setString(1,tenant);s.setInt(2,limit);try(var r=s.executeQuery()){while(r.next())jobs.add(new Due(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5)));}}
            int processed=0;for(Due job:jobs){
                if(!lock(c,tenant,job.ticket(),true))continue;
                try(var s=statement(c,"SELECT 1 FROM public.mop_ticket_workflow_clock_receipt WHERE task_id=?::uuid")){s.setString(1,job.id());try(var r=s.executeQuery()){if(r.next())continue;}}
                Event current=latest(c,tenant,job.ticket());if(current==null)throw unavailable();Instant at=now(c);String outcome="OBSOLETE";
                if(job.kind().equals("AUTO_CLOSE")&&job.cycle()==current.snapshot().confirmationCycle()){
                    // Keep the durable task pending; a later review must be able to unblock it.
                    if(!linkedEventsReviewed(c,tenant,job.ticket()))continue;
                    var change=WorkflowLifecycle.autoClose(current.snapshot(),at);
                    if(change.isPresent()){
                        append(c,tenant,job.ticket(),null,"AUTO_CLOSE",change.get().snapshot(),map("clock_task_id",job.id(),"note",null),change.get().effects());
                        platform.audit(null,tenant,"ticket.workflow.auto_close","ticket",job.ticket(),"SUCCEEDED","SYSTEM_DURABLE_CONFIRMATION_CLOCK","ticket-clock-"+job.id());outcome="AUTO_CLOSED";
                    }
                }else if(!job.kind().equals("AUTO_CLOSE")){
                    var recovery=monitoringRecovery(c,tenant,job.ticket());
                    List<ClockNotice> notices = WorkflowLifecycle.dueNotices(
                        current.snapshot(), at, recovery.linked(), recovery.recoveredAt()
                    );
                    MonitoringRecurrenceClock cycle;
                    try {
                        cycle = MonitoringRecurrenceClock.parse(job.key());
                    } catch (IllegalArgumentException invalidCycle) {
                        throw unavailable();
                    }
                    String canonicalKey = cycle == null ? job.key() : cycle.canonicalKey();
                    boolean eligible = cycle == null
                        ? !job.key().startsWith("recovery:") || !hasMonitoringRecurrence(c, tenant, job.ticket())
                        : currentMonitoringCycle(c, tenant, job.ticket(), cycle);
                    Optional<ClockNotice> notice = eligible
                        ? notices.stream().filter(n -> n.key().equals(canonicalKey)).findFirst()
                        : Optional.empty();
                    if(notice.isPresent()){outbox(c,tenant,job.ticket(),current,"clock:"+job.key(),notice.get().eventType(),at);outcome="EMITTED";}
                }
                try(var s=statement(c,"INSERT INTO public.mop_ticket_workflow_clock_receipt(task_id,tenant_id,outcome,completed_at) VALUES(?::uuid,?::uuid,?,?)")){s.setString(1,job.id());s.setString(2,tenant);s.setString(3,outcome);instant(s,4,at);s.executeUpdate();}processed++;
            }return processed;
        }catch(SQLException e){WorkflowAuthorityConflict.rejectIfConflict(e);throw unavailable();}});
    }
    private Map<String,Object> view(Access a,Parent p,Event event,String role){
        Snapshot s=event==null?null:event.snapshot();boolean customer=Capabilities.customer(role);Instant observed=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        return map("ticket_id",p.id(),"tenant_id",a.tenantId(),"title",p.title(),"receipt_no",p.receipt(),"registration_created_at",p.created().toString(),
            "version",s==null?0:s.version(),"state",s==null?null:s.state().name(),"priority",s==null?null:s.sla().priority(),"service_tier",s==null?null:s.sla().tier().name(),
            "assignee_id",s==null||customer?null:s.assigneeId(),"collaborator_ids",s==null||customer?List.of():s.collaborators(),"accepted_at",s==null?null:str(s.acceptedAt()),
            "resolved_at",s==null?null:str(s.resolvedAt()),"closed_at",s==null?null:str(s.closedAt()),"confirm_due_at",s==null?null:str(s.confirmDueAt()),"close_mode",s==null?null:s.closeMode(),
            "reopen_count",s==null?0:s.reopenCount(),"confirmation_cycle",s==null?0:s.confirmationCycle(),"resolution_note",s==null?null:s.resolutionNote(),
            "response_due_at",s==null?null:str(s.sla().responseDueAt()),"recovery_due_at",s==null?null:str(s.sla().recoveryDueAt()),"sla_missing",s==null?Map.of():s.sla().missing(),
            "sla_references",s==null||customer?List.of():s.sla().references(),"snapshot_hash",event==null?null:event.hash(),"updated_at",s==null?null:s.updatedAt().toString(),
            "observed_at",observed.toString(),"authority_source","MOP","source","postgresql","scope",customer?"REPORTER_ONLY":"CURRENT_TENANT_INTERNAL",
            "customer_confirmation_status","UNVERIFIED","production_approved",false,"external_notification_delivery","NOT_CONFIRMED");
    }
    private static String member(Connection c,Access a,String capability,boolean lock)throws SQLException{
        if(a==null)throw expired();PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),capability);
        try(var s=statement(c,"SELECT m.role_code FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active AND m.permission_version=?"+(lock?" FOR SHARE OF m,u":""))){s.setString(1,a.tenantId());s.setString(2,a.actorId());s.setLong(3,a.permissionVersion());try(var r=s.executeQuery()){if(!r.next())throw expired();return r.getString(1);}}
    }
    private static Parent parent(Connection c,Access a,String id,String role,boolean lock)throws SQLException{
        uuid(id);try(var s=statement(c,"SELECT id::text,reporter_id::text,title,receipt_no,created_at FROM public.mop_ticket WHERE tenant_id=?::uuid AND id=?::uuid"+(Capabilities.customer(role)?" AND reporter_id=?::uuid":"")+(lock?" FOR SHARE":""))){s.setString(1,a.tenantId());s.setString(2,id);if(Capabilities.customer(role))s.setString(3,a.actorId());try(var r=s.executeQuery()){if(!r.next())throw missing();return new Parent(r.getString(1),r.getString(2),r.getString(3),r.getString(4),time(r,"created_at"));}}
    }
    private static void assignee(Connection c,String tenant,String id)throws SQLException{
        uuid(id);try(var s=statement(c,"SELECT 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active AND m.role_code IN ('msp','padmin') AND 'ticket_notes:write_internal'=ANY(public.mop_effective_capabilities(u.id,m.tenant_id)) FOR SHARE OF m,u")){s.setString(1,tenant);s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())throw missing();}}
    }
    private Event latest(Connection c,String tenant,String ticket)throws SQLException{
        try(var s=statement(c,"SELECT id::text,snapshot,snapshot_hash FROM public.mop_ticket_workflow_event WHERE tenant_id=?::uuid AND ticket_id=?::uuid ORDER BY version DESC LIMIT 1")){s.setString(1,tenant);s.setString(2,ticket);try(var r=s.executeQuery()){return r.next()?new Event(r.getString(1),decode(r.getString(2)),r.getString(3)):null;}}
    }
    /** A raw RECOVERED flag, old unproved rows, or raw storage alone cannot authorize SLA completion/closure. */
    private static final String RECOVERY_PROOF = "EXISTS(SELECT 1 FROM public.mop_monitoring_event_member rm "
        + "JOIN public.mop_raw_monitoring_event rp ON rp.id=rm.raw_event_id AND rp.tenant_id=rm.tenant_id "
        + "WHERE rm.tenant_id=e.tenant_id AND rm.event_id=e.id AND rm.source_phase='RECOVERED' "
        + "AND rm.occurred_at=e.recovered_at AND rp.source_phase='RECOVERED' AND rp.asset_id=e.asset_id AND rp.source_event_id=rm.source_event_id "
        + "AND rp.source_event_id LIKE 'recovery-observation:'||e.id::text||':%' "
        + "AND EXISTS(SELECT 1 FROM public.mop_audit ra WHERE ra.tenant_id=e.tenant_id "
        + "AND ra.payload->>'action'='monitoring.recovery.observe' AND ra.payload->>'resource_type'='monitoring_recovery_observation' "
        + "AND ra.payload->>'resource_id'=rp.id::text AND ra.payload->>'result'='SUCCEEDED' "
        + "AND position('_RESULT_RECOVERY_CONFIRMED_RELEASE_' IN ra.payload->>'reason')>0))";
    static boolean recoveryConfirmed(Connection c, String tenant, String eventId) throws SQLException {
        try (var s = statement(c, "SELECT monitor_state='RECOVERED' AND recovered_at IS NOT NULL AND "
                + RECOVERY_PROOF + " FROM public.mop_monitoring_event e WHERE tenant_id=?::uuid AND id=?::uuid")) {
            s.setString(1, tenant);
            s.setString(2, eventId);
            try (var r = s.executeQuery()) {
                return r.next() && r.getBoolean(1);
            }
        }
    }
    private static boolean linkedEventsReviewed(Connection c,String tenant,String ticket)throws SQLException{
        try(var s=statement(c,"SELECT NOT EXISTS(SELECT 1 FROM public.mop_monitoring_event e WHERE tenant_id=?::uuid AND ticket_id=?::uuid AND (monitor_state<>'RECOVERED' OR handling_state NOT IN ('RESOLVED','CLOSED') OR NOT " + RECOVERY_PROOF + "))")){
            s.setString(1,tenant);s.setString(2,ticket);try(var r=s.executeQuery()){r.next();return r.getBoolean(1);}
        }
    }
    record MonitoringRecovery(boolean linked,Instant recoveredAt){}
    static MonitoringRecovery monitoringRecovery(Connection c,String tenant,String ticket)throws SQLException{
        try(var s=statement(c,"SELECT count(*)>0,CASE WHEN bool_and(monitor_state='RECOVERED' AND recovered_at IS NOT NULL AND " + RECOVERY_PROOF + ") THEN max(recovered_at) ELSE NULL END FROM public.mop_monitoring_event e WHERE tenant_id=?::uuid AND ticket_id=?::uuid")){
            s.setString(1,tenant);s.setString(2,ticket);try(var r=s.executeQuery()){r.next();return new MonitoringRecovery(r.getBoolean(1),r.getObject(2)==null?null:r.getObject(2,OffsetDateTime.class).toInstant());}
        }
    }
    private static boolean lock(Connection c,String tenant,String ticket,boolean attempt)throws SQLException{
        try(var s=statement(c,"SELECT "+(attempt?"pg_try_advisory_xact_lock":"pg_advisory_xact_lock")+"(hashtextextended(?,0))")){s.setString(1,"mop.ticket.workflow|"+tenant+"|"+ticket);try(var r=s.executeQuery()){r.next();return !attempt||r.getBoolean(1);}}
    }
    @Override public List<String> readinessProblems(){
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);try(var s=statement(c,"SELECT current_database()='mop_test' AND current_user='mop_test_runtime'");var r=s.executeQuery()){if(!r.next()||!r.getBoolean(1))throw new SQLException();}
            for(String table:List.of("mop_ticket_workflow_event","mop_ticket_workflow_outbox","mop_ticket_workflow_clock","mop_ticket_workflow_clock_receipt")){
                try(var s=statement(c,"SELECT has_table_privilege(current_user,?,'SELECT') AND has_table_privilege(current_user,?,'INSERT') AND NOT has_table_privilege(current_user,?,'UPDATE,DELETE,TRUNCATE') AND NOT has_any_column_privilege(current_user,?,'UPDATE')")){for(int n=1;n<=4;n++)s.setString(n,"public."+table);try(var r=s.executeQuery()){if(!r.next()||!r.getBoolean(1))throw new SQLException();}}
            }
            try(var s=statement(c,"SELECT count(*)=4 FROM pg_trigger WHERE tgname IN ('mop_ticket_workflow_guard','mop_ticket_workflow_outbox_immutable','mop_ticket_workflow_clock_immutable','mop_ticket_workflow_receipt_immutable') AND tgenabled='O'");var r=s.executeQuery()){if(!r.next()||!r.getBoolean(1))throw new SQLException();}
            return List.of();
        }catch(SQLException|RuntimeException e){return List.of("TICKET_WORKFLOW_SCHEMA_OR_ACCESS_UNAVAILABLE");}
    }
    private Snapshot decode(String json){try{return mapper.readValue(json,Snapshot.class);}catch(Exception e){throw unavailable();}}
    @SuppressWarnings("unchecked") private Map<String,Object> json(String json){try{return mapper.readValue(json,LinkedHashMap.class);}catch(Exception e){throw unavailable();}}
    private String encode(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw unavailable();}}
    private static String digest(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static void instant(PreparedStatement s,int n,Instant t)throws SQLException{s.setObject(n,OffsetDateTime.ofInstant(t,ZoneOffset.UTC));}
    private static Instant time(ResultSet r,String key)throws SQLException{return r.getObject(key,OffsetDateTime.class).toInstant();}
    private static String str(Instant t){return t==null?null:t.toString();}
    private static Instant now(Connection c)throws SQLException{try(var s=statement(c,"SELECT date_trunc('milliseconds',clock_timestamp())");var r=s.executeQuery()){r.next();return r.getObject(1,OffsetDateTime.class).toInstant();}}
    private static void uuid(String v){try{if(v==null||!UUID.fromString(v).toString().equals(v))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw invalid();}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","工单参数无效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","工单或人员不存在或不可见");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","成员关系或权限版本已失效");}
    private static DomainException conflict(){return new DomainException(409,"VERSION_CONFLICT","工单处理版本已变化");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","工单持久化结构或依赖不可用，未返回替代结果");}
}
