package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.ticket.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Independent, leased consumer. No customer impersonation, notification delivery or migration. */
public final class PostgresTicketAutoCloseRepository {
    public record Claim(String tenant,String ticket,long solutionVersion,String hash,String owner,long token,int attempts) {
        public Claim {
            if(tenant==null||ticket==null||!UUID.fromString(tenant).toString().equals(tenant)||!UUID.fromString(ticket).toString().equals(ticket)
                    ||solutionVersion<1||solutionVersion>9007199254740990L||hash==null||!hash.matches("[0-9a-f]{64}")
                    ||owner==null||!owner.matches("[A-Za-z0-9._:-]{1,128}")||token<1||token>9007199254740990L||attempts<1)
                throw new IllegalArgumentException("Invalid fenced auto-close claim");
        }
    }
    private final DataSource source;
    private final PostgresPlatformRepository platform;
    private final ObjectMapper mapper;
    public PostgresTicketAutoCloseRepository(DataSource source,PostgresPlatformRepository platform,ObjectMapper mapper){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);this.mapper=Objects.requireNonNull(mapper);}
    public Optional<Claim> claim(String owner,int leaseSeconds){
        if(owner==null||!owner.matches("[A-Za-z0-9._:-]{1,128}")||leaseSeconds<5||leaseSeconds>300)throw new IllegalArgumentException("Explicit bounded worker owner/lease required");
        return platform.ticketJobTransaction(()->{Connection c=platform.ticketJobConnection();try{
            String sql="WITH chosen AS (SELECT tenant_id,ticket_id,solution_version FROM public.mop_ticket_autoclose_job WHERE due_at<=clock_timestamp() AND (status='PENDING' OR (status='LEASED' AND lease_until<=clock_timestamp())) AND fencing_token<9007199254740990 AND attempts<2147483647 ORDER BY due_at,tenant_id,ticket_id,solution_version LIMIT 1 FOR UPDATE SKIP LOCKED) UPDATE public.mop_ticket_autoclose_job j SET status='LEASED',lease_owner=?,lease_until=date_trunc('milliseconds',clock_timestamp())+(?*interval '1 second'),fencing_token=j.fencing_token+1,attempts=j.attempts+1,last_error=NULL FROM chosen x WHERE j.tenant_id=x.tenant_id AND j.ticket_id=x.ticket_id AND j.solution_version=x.solution_version RETURNING j.*";
            try(var s=statement(c,sql)){s.setString(1,owner);s.setInt(2,leaseSeconds);try(var r=s.executeQuery()){if(!r.next())return Optional.empty();return Optional.of(new Claim(r.getString("tenant_id"),r.getString("ticket_id"),r.getLong("solution_version"),r.getString("solution_hash"),owner,r.getLong("fencing_token"),r.getInt("attempts")));}}
        }catch(SQLException e){throw unavailable();}});
    }
    public boolean complete(Claim claim,int maxAttempts){
        Objects.requireNonNull(claim);if(maxAttempts<1||maxAttempts>100)throw new IllegalArgumentException("Explicit bounded attempt limit required");
        return platform.ticketJobTransaction(()->{Connection c=platform.ticketJobConnection();try{
            // Same ticket lock as every manual lifecycle command; lock order is ticket then job.
            try(var s=statement(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){s.setString(1,"mop.ticket.lifecycle|"+claim.tenant()+"|"+claim.ticket());s.execute();}
            try(var s=statement(c,"SELECT lease_until,status,lease_owner,fencing_token,attempts FROM public.mop_ticket_autoclose_job WHERE tenant_id=?::uuid AND ticket_id=?::uuid AND solution_version=? FOR UPDATE")){
                identity(s,claim);try(var r=s.executeQuery()){
                    if(!r.next()||!"LEASED".equals(r.getString("status"))||!claim.owner().equals(r.getString("lease_owner"))||claim.token()!=r.getLong("fencing_token")||claim.attempts()!=r.getInt("attempts")||!time(r,"lease_until").isAfter(now(c)))return false;
                }
            }
            if(claim.attempts()>maxAttempts)return finish(c,claim,"BLOCKED_ATTEMPTS","ATTEMPT_LIMIT_REACHED");
            if(claim.solutionVersion()>=9007199254740990L)return finish(c,claim,"BLOCKED_ATTEMPTS","VERSION_LIMIT_REACHED");
            TicketLifecycle.Snapshot before;String opening;
            try(var s=statement(c,"SELECT * FROM public.mop_ticket_lifecycle_revision WHERE tenant_id=?::uuid AND ticket_id=?::uuid ORDER BY version DESC LIMIT 1")){
                s.setString(1,claim.tenant());s.setString(2,claim.ticket());try(var r=s.executeQuery()){
                    if(!r.next())throw unavailable();
                    if(r.getLong("version")!=claim.solutionVersion()||!"PENDING_CUSTOMER".equals(r.getString("state"))||!claim.hash().equals(r.getString("solution_snapshot_hash")))return finish(c,claim,"SUPERSEDED","CUSTOMER_OR_NEWER_REVISION_WON");
                    before=new TicketLifecycle.Snapshot(claim.tenant(),TicketLifecycle.State.PENDING_CUSTOMER,claim.solutionVersion(),r.getInt("reopen_count"),time(r,"occurred_at"),time(r,"resolved_at"),null,claim.hash(),null,time(r,"response_due_at"),time(r,"recovery_due_at"));opening=r.getString("opening_snapshot");
                }
            }
            verifySolution(c,claim,before);
            var configuration=mapper.readTree(opening);var hours=configuration.get("confirmation_timeout_hours");
            if(hours==null||!hours.isIntegralNumber()||!hours.canConvertToInt()||hours.intValue()<1)throw unavailable();
            Instant now=now(c);var decision=TicketLifecycle.timeoutCandidate(before,before.version(),now,new TicketLifecycle.Policy(null,Duration.ofHours(hours.intValue()),null,null));
            if(decision.after().state()!=TicketLifecycle.State.CLOSED)throw unavailable();
            String insert="INSERT INTO public.mop_ticket_lifecycle_revision(tenant_id,ticket_id,version,action,state,actor_id,actor_permission_version,actor_kind,occurred_at,reason,assignee_id,collaborator_ids,reopen_count,resolved_at,closed_at,solution_snapshot_hash,close_mode,response_due_at,recovery_due_at,opening_snapshot,production_approved,worker_lease_owner,worker_fencing_token) SELECT tenant_id,ticket_id,version+1,'AUTO_CLOSE','CLOSED',NULL,NULL,'WORKER',?,NULL,assignee_id,collaborator_ids,reopen_count,resolved_at,?,solution_snapshot_hash,'TIMEOUT_AUTO',response_due_at,recovery_due_at,opening_snapshot,false,?,? FROM public.mop_ticket_lifecycle_revision WHERE tenant_id=?::uuid AND ticket_id=?::uuid AND version=?";
            try(var s=statement(c,insert)){s.setObject(1,utc(now));s.setObject(2,utc(now));s.setString(3,claim.owner());s.setLong(4,claim.token());s.setString(5,claim.tenant());s.setString(6,claim.ticket());s.setLong(7,claim.solutionVersion());if(s.executeUpdate()!=1)throw unavailable();}
            return finish(c,claim,"SUCCEEDED","TIMEOUT_AUTO_CLOSED_NO_NOTIFICATION");
        }catch(SQLException|java.io.IOException e){throw unavailable();}});
    }
    private boolean finish(Connection c,Claim claim,String status,String reason)throws SQLException{
        try(var s=statement(c,"UPDATE public.mop_ticket_autoclose_job SET status=?,lease_owner=NULL,lease_until=NULL,completed_at=date_trunc('milliseconds',clock_timestamp()),last_error=? WHERE tenant_id=?::uuid AND ticket_id=?::uuid AND solution_version=? AND status='LEASED' AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp()")){
            s.setString(1,status);s.setString(2,status.equals("SUCCEEDED")?null:reason);s.setString(3,claim.tenant());s.setString(4,claim.ticket());s.setLong(5,claim.solutionVersion());s.setString(6,claim.owner());s.setLong(7,claim.token());if(s.executeUpdate()!=1)throw unavailable();
        }
        platform.auditTicketWorker(claim.tenant(),claim.ticket(),status,reason,"ticket-autoclose-"+claim.ticket()+"-"+claim.solutionVersion()+"-"+claim.token());return true;
    }
    private static void verifySolution(Connection c,Claim claim,TicketLifecycle.Snapshot before)throws SQLException{
        try(var s=statement(c,"SELECT document,engineer_id::text,resolved_at FROM public.mop_ticket_resolution_snapshot WHERE tenant_id=?::uuid AND ticket_id=?::uuid AND lifecycle_version=? AND document_sha256=?")){
            identity(s,claim);s.setString(4,claim.hash());try(var r=s.executeQuery()){
                if(!r.next())throw unavailable();var frozen=TicketResolutionSnapshot.read(r.getBytes("document"),claim.hash());
                if(!frozen.tenantId().toString().equals(claim.tenant())||!frozen.ticketId().toString().equals(claim.ticket())||frozen.lifecycleVersion()!=claim.solutionVersion()||!frozen.engineerId().toString().equals(r.getString("engineer_id"))||!frozen.resolvedAt().equals(before.resolvedAt())||!frozen.resolvedAt().equals(time(r,"resolved_at")))throw unavailable();
            }
        }
    }
    public boolean ready(){try(Connection c=source.getConnection()){c.setReadOnly(true);TicketAutoCloseSchemaVerifier.verify(c);return true;}catch(Exception unavailable){return false;}}
    private static void identity(PreparedStatement s,Claim claim)throws SQLException{s.setString(1,claim.tenant());s.setString(2,claim.ticket());s.setLong(3,claim.solutionVersion());}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static Instant now(Connection c)throws SQLException{try(var s=statement(c,"SELECT date_trunc('milliseconds',clock_timestamp())");var r=s.executeQuery()){r.next();return r.getObject(1,OffsetDateTime.class).toInstant();}}
    private static Instant time(ResultSet r,String key)throws SQLException{var value=r.getObject(key,OffsetDateTime.class);return value==null?null:value.toInstant();}
    private static OffsetDateTime utc(Instant time){return time.atOffset(ZoneOffset.UTC);}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","自动关单依赖或租约不可用，未返回替代成功");}
}
