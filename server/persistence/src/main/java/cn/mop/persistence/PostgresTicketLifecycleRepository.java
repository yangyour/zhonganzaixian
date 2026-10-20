package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.ticket.*;
import cn.mop.domain.ticket.TicketLifecycleRepository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Append + resolution evidence + audit share the platform's idempotent transaction.
 * Disabled by default at construction; future API wiring must supply a verified opening configuration.
 */
public final class PostgresTicketLifecycleRepository implements TicketLifecycleRepository {
    private final DataSource source;
    private final PostgresPlatformRepository platform;
    private final ObjectMapper mapper;
    private final boolean enabled;
    public PostgresTicketLifecycleRepository(DataSource source,PostgresPlatformRepository platform,ObjectMapper mapper,boolean enabled){
        this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);
        this.mapper=Objects.requireNonNull(mapper);this.enabled=enabled;
    }
    @FunctionalInterface private interface Read<T>{T run(Connection c)throws SQLException;}
    private <T>T read(Access a,String ticket,Read<T> work){
        requireEnabled();
        platform.requireStandaloneRead();
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{authorize(c,a,ticket,null);T value=work.run(c);c.commit();return value;}
            catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }catch(SQLException e){throw unavailable();}
    }
    @Override public Optional<Entry> get(Access a,String ticket){
        uuid(ticket);return read(a,ticket,c->latest(c,a,ticket));
    }
    @Override public List<Entry> history(Access a,String ticket,long before,int size){
        uuid(ticket);if(before<1||size<1||size>101)throw invalid();
        return read(a,ticket,c->{var result=new ArrayList<Entry>();
            try(var s=c.prepareStatement("SELECT * FROM public.mop_ticket_lifecycle_revision WHERE tenant_id=?::uuid AND ticket_id=?::uuid AND version<? ORDER BY version DESC LIMIT ?")){
                s.setQueryTimeout(10);s.setString(1,a.tenantId());s.setString(2,ticket);s.setLong(3,before);s.setInt(4,size);
                try(var rows=s.executeQuery()){while(rows.next()){Entry e=entry(rows);verifyResolution(c,a,e);result.add(e);}}
            }return List.copyOf(result);
        });
    }
    @Override public void authorizeCurrentAccess(Access a,String ticket,boolean customer){
        requireEnabled();Connection c=platform.idempotentCommandConnection();
        try{authorize(c,a,ticket,customer?"tickets:customer_decide":"tickets:lifecycle_write");}
        catch(SQLException e){throw unavailable();}
    }
    @Override public Entry start(Access a,String ticket,String json,Instant responseDue,Instant recoveryDue){
        requireEnabled();Connection c=platform.idempotentCommandConnection();
        JsonNode configuration=configuration(json);
        if(!configuration.path("enabled").isBoolean()||!configuration.path("enabled").booleanValue()
                ||!configuration.path("configuration_release_ref").isTextual()
                ||configuration.path("configuration_release_ref").textValue().isBlank()
                ||configuration.path("configuration_release_ref").textValue().length()>256
                ||!configuration.hasNonNull("service_tier")||!configuration.hasNonNull("priority")
                ||responseDue==null||recoveryDue==null)throw pending();
        if(responseDue.getNano()%1_000_000!=0||recoveryDue.getNano()%1_000_000!=0
                ||responseDue.isAfter(Instant.parse("9999-12-31T23:59:59.999Z"))
                ||recoveryDue.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();
        try{
            Membership actor=authorize(c,a,ticket,"tickets:lifecycle_write");
            if(!Capabilities.internal(actor.role()))throw forbidden();
            lock(c,a,ticket);if(latest(c,a,ticket).isPresent())throw conflict();
            Instant now=now(c);
            if(responseDue.isBefore(now)||recoveryDue.isBefore(now))throw invalid();
            var snapshot=new TicketLifecycle.Snapshot(a.tenantId(),TicketLifecycle.State.NEW,1,0,now,null,null,null,null,responseDue,recoveryDue);
            var result=new Entry(ticket,snapshot,null,List.of(),json);
            append(c,a,result,"CREATE",null);
            platform.audit(a.actorId(),a.tenantId(),"ticket.lifecycle.create","ticket",ticket,"SUCCEEDED","TEST_LIFECYCLE_OPENING_SNAPSHOT",a.traceId());
            return result;
        }catch(SQLException e){throw unavailable();}
    }
    @Override public Entry act(Access a,String ticket,Action action){
        requireEnabled();if(action==null||action.action()==null)throw invalid();
        boolean customer=Set.of(TicketLifecycle.Action.CONFIRM,TicketLifecycle.Action.NOT_RESOLVED,TicketLifecycle.Action.REOPEN).contains(action.action());
        if(action.action()==TicketLifecycle.Action.AUTO_CLOSE)throw forbidden();
        Connection c=platform.idempotentCommandConnection();
        try{
            Membership actor=authorize(c,a,ticket,customer?"tickets:customer_decide":"tickets:lifecycle_write");
            lock(c,a,ticket);Entry before=latest(c,a,ticket).orElseThrow(PostgresTicketLifecycleRepository::missing);
            if(before.snapshot().version()!=action.expectedVersion()||before.snapshot().version()>=9007199254740990L)throw conflict();
            Instant now=now(c);String hash=action.expectedSolutionHash();
            TicketResolutionSnapshot resolution=null;
            if(action.action()==TicketLifecycle.Action.SUBMIT_RESOLUTION){
                resolution=new TicketResolutionSnapshot(uuid(a.tenantId()),uuid(ticket),Math.addExact(action.expectedVersion(),1),
                        uuid(a.actorId()),now,title(c,a,ticket),action.reason());
                hash=resolution.sha256();
            }
            var command=new TicketLifecycle.Command(action.action(),action.expectedVersion(),now,action.reason(),hash);
            TicketLifecycle.Policy policy;
            try{policy=policy(configuration(before.openingSnapshotJson()));}
            catch(IllegalArgumentException e){throw unavailable();}
            var decision=customer?TicketLifecycle.customer(before.snapshot(),command,policy,actor,
                    new TicketCustomerActionRules.Relationships(a.tenantId(),reporter(c,a,ticket),Set.of(),true)):
                    TicketLifecycle.engineer(before.snapshot(),command,policy,actor);
            String assignee=before.assigneeId();var collaborators=new LinkedHashSet<>(before.collaboratorIds());
            if(action.action()==TicketLifecycle.Action.ACCEPT)assignee=a.actorId();
            if(action.action()==TicketLifecycle.Action.TRANSFER){
                assignee=uuid(action.assigneeId()).toString();
                if(action.keepPriorAssignee()&&before.assigneeId()!=null)collaborators.add(before.assigneeId());
                collaborators.remove(assignee);
            }else if(action.assigneeId()!=null||action.keepPriorAssignee())throw invalid();
            if(resolution!=null)saveResolution(c,resolution);
            var result=new Entry(ticket,decision.after(),assignee,List.copyOf(collaborators),before.openingSnapshotJson());
            append(c,a,result,action.action().name(),action.reason());
            platform.audit(a.actorId(),a.tenantId(),"ticket.lifecycle."+action.action().name().toLowerCase(Locale.ROOT),
                    "ticket",ticket,"SUCCEEDED","LIFECYCLE_REVISION_NOTIFICATION_INTEGRATION_PENDING",a.traceId());
            return result;
        }catch(SQLException e){throw unavailable();}
    }
    private Membership authorize(Connection c,Access a,String ticket,String capability)throws SQLException{
        if(a==null)throw new DomainException(401,"SESSION_EXPIRED","当前成员身份已失效");
        uuid(ticket);PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),capability==null?"tickets:read":capability);
        try(var s=c.prepareStatement("SELECT m.role_code FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id JOIN public.mop_ticket t ON t.tenant_id=m.tenant_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND t.id=?::uuid AND m.active AND u.active AND m.permission_version=? AND (m.role_code IN ('msp','padmin') OR t.reporter_id=m.user_id)"+(capability==null?"":" FOR SHARE OF m,u,t"))){
            s.setQueryTimeout(10);s.setString(1,a.tenantId());s.setString(2,a.actorId());s.setString(3,ticket);s.setLong(4,a.permissionVersion());
            try(var r=s.executeQuery()){if(!r.next())throw missing();return new Membership(a.actorId(),a.tenantId(),r.getString(1),a.permissionVersion(),true);}
        }
    }
    private static void lock(Connection c,Access a,String ticket)throws SQLException{
        try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){
            s.setQueryTimeout(10);s.setString(1,"mop.ticket.lifecycle|"+a.tenantId()+"|"+ticket);s.execute();
        }
    }
    private static Optional<Entry> latest(Connection c,Access a,String ticket)throws SQLException{
        try(var s=c.prepareStatement("SELECT * FROM public.mop_ticket_lifecycle_revision WHERE tenant_id=?::uuid AND ticket_id=?::uuid ORDER BY version DESC LIMIT 1")){
            s.setQueryTimeout(10);s.setString(1,a.tenantId());s.setString(2,ticket);try(var r=s.executeQuery()){
                if(!r.next())return Optional.empty();Entry e=entry(r);verifyResolution(c,a,e);return Optional.of(e);
            }
        }
    }
    private static void verifyResolution(Connection c,Access a,Entry e)throws SQLException{
        String hash=e.snapshot().solutionSnapshotHash();if(hash==null)return;
        try(var s=c.prepareStatement("SELECT lifecycle_version,engineer_id::text,resolved_at,document FROM public.mop_ticket_resolution_snapshot WHERE tenant_id=?::uuid AND ticket_id=?::uuid AND document_sha256=?")){
            s.setQueryTimeout(10);s.setString(1,a.tenantId());s.setString(2,e.ticketId());s.setString(3,hash);
            try(var r=s.executeQuery()){
                if(!r.next())throw unavailable();
                TicketResolutionSnapshot frozen;
                try{frozen=TicketResolutionSnapshot.read(r.getBytes("document"),hash);}
                catch(RuntimeException corrupt){throw unavailable();}
                if(!frozen.tenantId().toString().equals(a.tenantId())||!frozen.ticketId().toString().equals(e.ticketId())
                        ||frozen.lifecycleVersion()!=r.getLong("lifecycle_version")||frozen.lifecycleVersion()>e.snapshot().version()
                        ||!frozen.engineerId().toString().equals(r.getString("engineer_id"))
                        ||!frozen.resolvedAt().equals(instant(r,"resolved_at"))
                        ||!frozen.resolvedAt().equals(e.snapshot().resolvedAt()))throw unavailable();
            }
        }
    }
    private static Entry entry(ResultSet r)throws SQLException{
        try{
            if(r.getBoolean("production_approved"))throw unavailable();
            var snapshot=new TicketLifecycle.Snapshot(r.getString("tenant_id"),TicketLifecycle.State.valueOf(r.getString("state")),r.getLong("version"),r.getInt("reopen_count"),
                    instant(r,"occurred_at"),instant(r,"resolved_at"),instant(r,"closed_at"),r.getString("solution_snapshot_hash"),
                    r.getString("close_mode")==null?null:TicketLifecycle.CloseMode.valueOf(r.getString("close_mode")),instant(r,"response_due_at"),instant(r,"recovery_due_at"));
            Object[] array=(Object[])r.getArray("collaborator_ids").getArray();var collaborators=Arrays.stream(array).map(Object::toString).toList();
            return new Entry(r.getString("ticket_id"),snapshot,r.getString("assignee_id"),collaborators,r.getString("opening_snapshot"));
        }catch(IllegalArgumentException|NullPointerException e){throw unavailable();}
    }
    private static void append(Connection c,Access a,Entry e,String action,String reason)throws SQLException{
        String sql="INSERT INTO public.mop_ticket_lifecycle_revision(tenant_id,ticket_id,version,action,state,actor_id,actor_permission_version,actor_kind,occurred_at,reason,assignee_id,collaborator_ids,reopen_count,resolved_at,closed_at,solution_snapshot_hash,close_mode,response_due_at,recovery_due_at,opening_snapshot) VALUES (?::uuid,?::uuid,?,?,?,?::uuid,?,'USER',?,?,?::uuid,?,?,?,?,?,?,?,?,?::jsonb)";
        var v=e.snapshot();try(var s=c.prepareStatement(sql)){
            s.setQueryTimeout(10);int i=1;s.setString(i++,a.tenantId());s.setString(i++,e.ticketId());s.setLong(i++,v.version());s.setString(i++,action);s.setString(i++,v.state().name());s.setString(i++,a.actorId());s.setLong(i++,a.permissionVersion());s.setObject(i++,utc(v.lastTransitionAt()));s.setString(i++,reason);s.setString(i++,e.assigneeId());
            s.setArray(i++,c.createArrayOf("uuid",e.collaboratorIds().toArray()));s.setInt(i++,v.reopenCount());s.setObject(i++,utc(v.resolvedAt()));s.setObject(i++,utc(v.closedAt()));s.setString(i++,v.solutionSnapshotHash());s.setString(i++,v.closeMode()==null?null:v.closeMode().name());s.setObject(i++,utc(v.responseDueAt()));s.setObject(i++,utc(v.recoveryDueAt()));s.setString(i,e.openingSnapshotJson());s.executeUpdate();
        }
    }
    private static void saveResolution(Connection c,TicketResolutionSnapshot r)throws SQLException{
        try(var s=c.prepareStatement("INSERT INTO public.mop_ticket_resolution_snapshot(tenant_id,ticket_id,lifecycle_version,engineer_id,resolved_at,document) VALUES (?::uuid,?::uuid,?,?::uuid,?,?)")){
            s.setQueryTimeout(10);s.setString(1,r.tenantId().toString());s.setString(2,r.ticketId().toString());s.setLong(3,r.lifecycleVersion());s.setString(4,r.engineerId().toString());s.setObject(5,utc(r.resolvedAt()));s.setBytes(6,r.canonicalBytes());s.executeUpdate();
        }
    }
    private static String ticketField(Connection c,Access a,String ticket,String field)throws SQLException{
        try(var s=c.prepareStatement("SELECT "+field+"::text FROM public.mop_ticket WHERE tenant_id=?::uuid AND id=?::uuid")){
            s.setQueryTimeout(10);s.setString(1,a.tenantId());s.setString(2,ticket);try(var r=s.executeQuery()){if(!r.next())throw missing();return r.getString(1);}
        }
    }
    private static String title(Connection c,Access a,String ticket)throws SQLException{return ticketField(c,a,ticket,"title");}
    private static String reporter(Connection c,Access a,String ticket)throws SQLException{return ticketField(c,a,ticket,"reporter_id");}
    private JsonNode configuration(String json){
        try{
            if(json==null||json.length()>16_384)throw invalid();JsonNode n=mapper.readTree(json);if(n==null||!n.isObject())throw invalid();
            Set<String> allowed=Set.of("enabled","configuration_release_ref","service_tier","priority","solution_min_chars",
                    "confirmation_timeout_hours","reopen_window_hours","manager_threshold");
            var names=n.fieldNames();while(names.hasNext())if(!allowed.contains(names.next()))throw invalid();
            if(n.hasNonNull("service_tier")&&(!n.get("service_tier").isTextual()
                    ||!Set.of("BASIC","STANDARD","ADVANCED").contains(n.get("service_tier").textValue())))throw invalid();
            if(n.hasNonNull("priority")&&(!n.get("priority").isTextual()
                    ||!Set.of("P1","P2","P3").contains(n.get("priority").textValue())))throw invalid();
            return n;
        }
        catch(java.io.IOException e){throw invalid();}
    }
    private static Integer integer(JsonNode n,String field){
        JsonNode value=n.get(field);if(value==null||value.isNull())return null;
        if(!value.isIntegralNumber()||!value.canConvertToInt())throw pending();return value.intValue();
    }
    private static TicketLifecycle.Policy policy(JsonNode n){
        Integer hours=integer(n,"confirmation_timeout_hours"),reopen=integer(n,"reopen_window_hours");
        return new TicketLifecycle.Policy(integer(n,"solution_min_chars"),hours==null?null:Duration.ofHours(hours),reopen==null?null:Duration.ofHours(reopen),integer(n,"manager_threshold"));
    }
    private void requireEnabled(){if(!enabled)throw pending();}
    private static Instant now(Connection c)throws SQLException{try(var s=c.createStatement()){s.setQueryTimeout(10);try(var r=s.executeQuery("SELECT date_trunc('milliseconds',clock_timestamp())")){r.next();return r.getObject(1,OffsetDateTime.class).toInstant();}}}
    private static Instant instant(ResultSet r,String name)throws SQLException{var t=r.getObject(name,OffsetDateTime.class);return t==null?null:t.toInstant();}
    private static OffsetDateTime utc(Instant time){return time==null?null:time.atOffset(ZoneOffset.UTC);}
    private static UUID uuid(String s){try{UUID u=UUID.fromString(s);if(!u.toString().equals(s))throw invalid();return u;}catch(IllegalArgumentException|NullPointerException e){throw invalid();}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","工单生命周期输入无效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException conflict(){return new DomainException(409,"VERSION_CONFLICT","工单版本已变化");}
    private static DomainException forbidden(){return new DomainException(403,"ACTION_FORBIDDEN","当前身份不能执行此动作");}
    private static DomainException pending(){return new DomainException(422,"CONFIG_PENDING","工单生命周期或所需规则尚未配置");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","工单生命周期存储或结构不可用");}
    @Override public List<String> readinessProblems(){
        if(!enabled)return List.of("TICKET_LIFECYCLE_DISABLED");
        try(Connection c=source.getConnection()){c.setReadOnly(true);TicketLifecycleSchemaVerifier.verify(c);return List.of();}
        catch(SQLException|RuntimeException e){return List.of("TICKET_LIFECYCLE_SCHEMA_UNAVAILABLE");}
    }
}
