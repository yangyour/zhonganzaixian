package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.sla.*;
import cn.mop.domain.ticket.TicketLifecycleRepository.SlaEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Transactional reminder deduplication and notification intents only. Never sends to a provider. */
public final class PostgresSlaReminderRepository {
    public record Claim(String tenant,String ticket,long version,String owner,long token,int attempts){
        public Claim {if(tenant==null||ticket==null||!UUID.fromString(tenant).toString().equals(tenant)||!UUID.fromString(ticket).toString().equals(ticket)||version<1||version>9007199254740990L||owner==null||!owner.matches("[A-Za-z0-9._:-]{1,128}")||token<1||token>9007199254740990L||attempts<1)throw new IllegalArgumentException("Invalid SLA scan claim");}
    }
    private record Contract(int tickSeconds,List<BigDecimal> thresholds){}
    private final DataSource source;private final PostgresPlatformRepository platform;private final ObjectMapper mapper;private final PostgresTicketLifecycleRepository lifecycle;private final java.util.function.Function<Claim,SlaEntry> projection;
    public PostgresSlaReminderRepository(DataSource source,PostgresPlatformRepository platform,ObjectMapper mapper){this(source,platform,mapper,null);}
    /** Isolated tests may supply a projection; runtime construction always uses the fenced transaction reader. */
    PostgresSlaReminderRepository(DataSource source,PostgresPlatformRepository platform,ObjectMapper mapper,java.util.function.Function<Claim,SlaEntry> projection){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);this.mapper=Objects.requireNonNull(mapper);this.lifecycle=new PostgresTicketLifecycleRepository(source,platform,mapper,true);this.projection=projection==null?c->lifecycle.projectForWorker(c.tenant(),c.ticket(),c.version()):projection;}
    public Optional<Claim> claim(String owner,int seconds){
        if(owner==null||!owner.matches("[A-Za-z0-9._:-]{1,128}")||seconds<5||seconds>300)throw new IllegalArgumentException("Explicit bounded SLA owner and lease required");
        return platform.ticketJobTransaction(()->{Connection c=platform.ticketJobConnection();try{
            String sql="WITH chosen AS (SELECT tenant_id,ticket_id FROM public.mop_ticket_sla_scan_job WHERE next_at<=clock_timestamp() AND (status='PENDING' OR (status='LEASED' AND lease_until<=clock_timestamp())) AND fencing_token<9007199254740990 AND attempts<2147483647 ORDER BY next_at,tenant_id,ticket_id LIMIT 1 FOR UPDATE SKIP LOCKED) UPDATE public.mop_ticket_sla_scan_job j SET status='LEASED',lease_owner=?,lease_until=date_trunc('milliseconds',clock_timestamp())+(?*interval '1 second'),fencing_token=j.fencing_token+1,attempts=j.attempts+1 FROM chosen x WHERE j.tenant_id=x.tenant_id AND j.ticket_id=x.ticket_id RETURNING j.*";
            try(var s=statement(c,sql)){s.setString(1,owner);s.setInt(2,seconds);try(var r=s.executeQuery()){if(!r.next())return Optional.empty();return Optional.of(new Claim(r.getString("tenant_id"),r.getString("ticket_id"),r.getLong("lifecycle_version"),owner,r.getLong("fencing_token"),r.getInt("attempts")));}}
        }catch(SQLException e){throw unavailable();}});
    }
    public boolean scan(Claim claim,int maxAttempts){
        Objects.requireNonNull(claim);if(maxAttempts<1||maxAttempts>100)throw new IllegalArgumentException("Explicit bounded SLA retry budget required");
        return platform.ticketJobTransaction(()->{Connection c=platform.ticketJobConnection();try{
            try(var s=statement(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){s.setString(1,"mop.ticket.lifecycle|"+claim.tenant()+"|"+claim.ticket());s.execute();}
            if(!owned(c,claim))return false;Contract contract=contract(c);Instant observed=now(c);
            if(claim.attempts()>maxAttempts){finish(c,claim,"BLOCKED_ATTEMPTS","ATTEMPT_LIMIT_REACHED",observed,contract.tickSeconds());platform.auditSlaWorker(claim.tenant(),claim.ticket(),"BLOCKED","ATTEMPT_LIMIT_REACHED",trace(claim));return true;}
            SlaEntry state=projection.apply(claim);if(state==null||!claim.tenant().equals(state.tenantId())||!claim.ticket().equals(state.ticketId())||state.clock().lifecycleVersion()!=claim.version())throw unavailable();observed=state.clock().observedAt();
            var recorded=recorded(c,claim);int count=0;boolean running=false,pending=false;
            for(SlaReminderPlanner.Clock kind:SlaReminderPlanner.Clock.values()){
                var clock=kind==SlaReminderPlanner.Clock.RESPONSE?state.clock().response():state.clock().recovery();
                if(clock.status()==SlaClockState.Status.CONFIG_PENDING){pending=true;continue;}
                if(clock.status()!=SlaClockState.Status.RUNNING)continue;running=true;
                if(clock.elapsed()==null||clock.budget()==null){pending=true;continue;}
                var prior=new HashSet<SlaReminderPlanner.Key>();for(var key:recorded)if(key.clock()==kind)prior.add(key);
                var plan=SlaReminderPlanner.plan(UUID.fromString(claim.tenant()),UUID.fromString(claim.ticket()),kind,SlaReminderPlanner.State.RUNNING,clock.elapsed(),clock.budget(),contract.thresholds(),prior);
                for(var key:plan.due())if(record(c,claim,state,key,clock))count++;
            }
            finish(c,claim,running||pending?"PENDING":"IDLE",pending?"CONFIG_PENDING":count>0?"INTENTS_RECORDED_NOT_DELIVERED":"NO_DUE_NODE",observed,contract.tickSeconds());
            if(count>0)platform.auditSlaWorker(claim.tenant(),claim.ticket(),"SUCCEEDED","RECORDED_NOT_DELIVERED_"+count,trace(claim));return true;
        }catch(SQLException e){throw unavailable();}});
    }
    private boolean record(Connection c,Claim claim,SlaEntry state,SlaReminderPlanner.Key key,SlaClockState.Clock clock)throws SQLException{
        String intent=UUID.randomUUID().toString();Instant observed=state.clock().observedAt();
        try(var s=statement(c,"INSERT INTO public.mop_ticket_sla_reminder(tenant_id,ticket_id,clock,progress,lifecycle_version,observed_at,elapsed_ms,budget_ms,configuration_reference,policy_reference,intent_id,worker_lease_owner,worker_fencing_token) VALUES(?::uuid,?::uuid,?,?,?,?,?,?,?,?,?::uuid,?,?) ON CONFLICT(tenant_id,ticket_id,clock,progress) DO NOTHING")){
            s.setString(1,claim.tenant());s.setString(2,claim.ticket());s.setString(3,key.clock().name());s.setBigDecimal(4,key.threshold());s.setLong(5,claim.version());s.setObject(6,utc(observed));s.setLong(7,clock.elapsed().toMillis());s.setLong(8,clock.budget().toMillis());s.setString(9,state.clock().configurationReference());s.setString(10,state.policyReference());s.setString(11,intent);s.setString(12,claim.owner());s.setLong(13,claim.token());if(s.executeUpdate()==0)return false;
        }
        var payload=mapper.createObjectNode().put("clock",key.clock().name()).put("progress",key.threshold()).put("lifecycle_version",claim.version()).put("configuration_reference",state.clock().configurationReference()).put("observed_at",observed.toString()).put("escalation_required",key.threshold().compareTo(BigDecimal.ONE)==0);
        if(state.policyReference()==null)payload.putNull("policy_reference");else payload.put("policy_reference",state.policyReference());
        try(var s=statement(c,"INSERT INTO public.mop_notification_intent(id,tenant_id,ticket_id,kind,event_key,payload,created_at) VALUES(?::uuid,?::uuid,?::uuid,'SLA_PROGRESS',?,?::jsonb,?)")){
            s.setString(1,intent);s.setString(2,claim.tenant());s.setString(3,claim.ticket());s.setString(4,"sla:"+claim.ticket()+":"+key.clock().name()+":"+key.threshold().toPlainString());s.setString(5,payload.toString());s.setObject(6,utc(observed));s.executeUpdate();
        }return true;
    }
    private Set<SlaReminderPlanner.Key> recorded(Connection c,Claim claim)throws SQLException{
        var out=new HashSet<SlaReminderPlanner.Key>();try(var s=statement(c,"SELECT clock,progress FROM public.mop_ticket_sla_reminder WHERE tenant_id=?::uuid AND ticket_id=?::uuid")){
            identity(s,claim);try(var r=s.executeQuery()){while(r.next())out.add(new SlaReminderPlanner.Key(UUID.fromString(claim.tenant()),UUID.fromString(claim.ticket()),SlaReminderPlanner.Clock.valueOf(r.getString(1)),r.getBigDecimal(2)));}
        }return out;
    }
    private boolean owned(Connection c,Claim claim)throws SQLException{
        try(var s=statement(c,"SELECT * FROM public.mop_ticket_sla_scan_job WHERE tenant_id=?::uuid AND ticket_id=?::uuid FOR UPDATE")){identity(s,claim);try(var r=s.executeQuery()){
            return r.next()&&"LEASED".equals(r.getString("status"))&&claim.version()==r.getLong("lifecycle_version")&&claim.owner().equals(r.getString("lease_owner"))&&claim.token()==r.getLong("fencing_token")&&claim.attempts()==r.getInt("attempts")&&r.getObject("lease_until",OffsetDateTime.class).toInstant().isAfter(now(c));
        }}
    }
    private void finish(Connection c,Claim claim,String status,String outcome,Instant observed,int tick)throws SQLException{
        try(var s=statement(c,"UPDATE public.mop_ticket_sla_scan_job SET status=?,next_at=?,lease_owner=NULL,lease_until=NULL,attempts=?,last_outcome=?,observed_at=? WHERE tenant_id=?::uuid AND ticket_id=?::uuid AND lifecycle_version=? AND status='LEASED' AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp()")){
            s.setString(1,status);s.setObject(2,utc(observed.plusSeconds(tick)));s.setInt(3,status.equals("BLOCKED_ATTEMPTS")?claim.attempts():0);s.setString(4,outcome);s.setObject(5,utc(observed));s.setString(6,claim.tenant());s.setString(7,claim.ticket());s.setLong(8,claim.version());s.setString(9,claim.owner());s.setLong(10,claim.token());if(s.executeUpdate()!=1)throw unavailable();
        }
    }
    private Contract contract(Connection c)throws SQLException{
        Integer tick=null;List<BigDecimal> progress=null;
        try(var s=statement(c,"SELECT key,registry_json FROM public.mop_configuration WHERE key IN ('sla.tick_interval_sec','sla.reminder_progress')");var r=s.executeQuery()){
            while(r.next()){try{var n=mapper.readTree(r.getString(2));if(!"fixed_constraint".equals(n.path("contract_value_kind").asText()))throw unavailable();var v=n.path("value");
                if(r.getString(1).equals("sla.tick_interval_sec")){if(!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()!=30)throw unavailable();tick=v.intValue();}
                else{if(!v.isArray()||v.size()!=3)throw unavailable();var p=new ArrayList<BigDecimal>();for(var element:v){if(!element.isNumber())throw unavailable();p.add(element.decimalValue());}if(p.get(0).compareTo(new BigDecimal("0.5"))!=0||p.get(1).compareTo(new BigDecimal("0.8"))!=0||p.get(2).compareTo(BigDecimal.ONE)!=0)throw unavailable();progress=List.copyOf(p);}
            }catch(java.io.IOException corrupt){throw unavailable();}}
        }
        if(tick==null||progress==null)throw unavailable();return new Contract(tick,progress);
    }
    public boolean ready(){try(Connection c=source.getConnection()){c.setReadOnly(true);TicketLifecycleSchemaVerifier.verify(c);SlaReminderSchemaVerifier.verify(c);contract(c);return true;}catch(Exception unavailable){return false;}}
    private static String trace(Claim c){return "sla-reminder-"+c.ticket()+"-"+c.token();}
    private static void identity(PreparedStatement s,Claim c)throws SQLException{s.setString(1,c.tenant());s.setString(2,c.ticket());}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static Instant now(Connection c)throws SQLException{try(var s=statement(c,"SELECT date_trunc('milliseconds',clock_timestamp())");var r=s.executeQuery()){r.next();return r.getObject(1,OffsetDateTime.class).toInstant();}}
    private static OffsetDateTime utc(Instant t){return t.atOffset(ZoneOffset.UTC);}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","SLA提醒依赖、固定配置或租约不可用，未发送通知");}
}
