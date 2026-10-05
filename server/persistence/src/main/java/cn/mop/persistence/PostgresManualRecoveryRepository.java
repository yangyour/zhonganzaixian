package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.manualincident.*;
import cn.mop.domain.manualincident.ManualRecoveryRepository.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Separate current human confirmation; never modifies immutable V13 original registration or monitoring/workflow state. */
public final class PostgresManualRecoveryRepository implements ManualRecoveryRepository {
    private static final System.Logger LOG=System.getLogger(PostgresManualRecoveryRepository.class.getName());
    private static final String SELECT="SELECT id::text,tenant_id::text,incident_id::text,actor_id::text,version,note,note_sha256,reported_recovered_at,recorded_at,manual_recovery_status,verification_status,monitor_state,disposition_state,monitoring_recovery_applicable,ticket_state_changed,sla_changed,record_source FROM public.mop_manual_recovery_confirmation";
    private final DataSource source;private final PostgresPlatformRepository platform;
    public PostgresManualRecoveryRepository(DataSource source,PostgresPlatformRepository platform){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);}
    @Override public Current get(Access a,String incidentId){String id=uuid(incidentId);
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{authorize(c,a,false);incident(c,a,id);Current result=current(c,a,id);c.commit();return result;}
            catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}
        }catch(SQLException failure){throw unavailable(failure);}
    }
    private static void authorize(Connection c,Access a,boolean lock)throws SQLException{
        if(a==null)throw new DomainException(401,"SESSION_EXPIRED","当前授权上下文无效");
        PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),lock?"manual_recovery:confirm":"manual_recovery:read");

        if(a==null||!validUuid(a.actorId())||!validUuid(a.tenantId())||a.permissionVersion()<1)throw expired();
        try(var s=statement(c,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""))){
            s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){if(!r.next())throw expired();ManualIncidentAccess.require(new Membership(a.actorId(),a.tenantId(),r.getString(1),r.getLong(2),true),a.permissionVersion(),a.tenantId());}
        }
    }
    private static void incident(Connection c,Access a,String id)throws SQLException{
        // V13 is immutable and runtime has no UPDATE privilege. The composite FK protects insertion;
        // never expand parent grants just to request FOR SHARE on this immutable record.
        try(var s=statement(c,"SELECT source_state FROM public.mop_manual_incident_registration WHERE tenant_id=?::uuid AND id=?::uuid")){s.setString(1,a.tenantId());s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())throw missing();if(!"MANUAL".equals(r.getString(1)))throw corrupt();}}
    }
    @Override public void authorizeCurrentIncident(Access a,String incidentId){Connection c=platform.idempotentCommandConnection();String id=uuid(incidentId);try{authorize(c,a,true);incident(c,a,id);}catch(SQLException failure){throw unavailable(failure);}}
    private static Current current(Connection c,Access a,String id)throws SQLException{
        try(var s=statement(c,SELECT+" WHERE tenant_id=?::uuid AND incident_id=?::uuid")){s.setString(1,a.tenantId());s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())return new Current(id,a.tenantId(),0,null);Confirmation value=confirmation(r);if(r.next())throw corrupt();return new Current(id,a.tenantId(),1,value);}}
    }
    @Override public Confirmation confirm(Access a,String incidentId,long expectedVersion,ManualRecoveryConfirmation value){
        Connection c=platform.idempotentCommandConnection();String id=uuid(incidentId);if(value==null||expectedVersion<0||expectedVersion>1)throw invalid();
        try{
            authorize(c,a,true);incident(c,a,id);
            try(var s=statement(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){s.setString(1,"manual-recovery|"+a.tenantId()+"|"+id);s.execute();}
            Current before=current(c,a,id);ManualRecoveryConfirmation.requireConfirmable(expectedVersion,before.version());
            Instant recordedAt;try(var s=statement(c,"SELECT statement_timestamp()");var r=s.executeQuery()){if(!r.next())throw corrupt();recordedAt=r.getObject(1,OffsetDateTime.class).toInstant().truncatedTo(ChronoUnit.MILLIS);}value.validateRecordedAt(recordedAt);
            String confirmationId=UUID.randomUUID().toString();
            try(var s=statement(c,"INSERT INTO public.mop_manual_recovery_confirmation(id,tenant_id,incident_id,actor_id,note,note_sha256,reported_recovered_at,recorded_at) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?)")){
                s.setString(1,confirmationId);s.setString(2,a.tenantId());s.setString(3,id);s.setString(4,a.actorId());s.setString(5,value.note());s.setString(6,value.noteSha256());if(value.reportedRecoveredAt()==null)s.setNull(7,Types.TIMESTAMP_WITH_TIMEZONE);else s.setObject(7,OffsetDateTime.ofInstant(value.reportedRecoveredAt(),ZoneOffset.UTC));s.setObject(8,OffsetDateTime.ofInstant(recordedAt,ZoneOffset.UTC));s.executeUpdate();
            }
            platform.audit(a.actorId(),a.tenantId(),"manual_incident.recovery.confirm","manual_recovery_confirmation",confirmationId,"SUCCEEDED","HUMAN_SELF_REPORTED_NO_MONITOR_OR_TICKET_TRANSITION",a.traceId());
            Confirmation saved=current(c,a,id).confirmation();if(saved==null||!saved.id().equals(confirmationId))throw corrupt();return saved;
        }catch(SQLException failure){if("23505".equals(failure.getSQLState()))throw new DomainException(409,"VERSION_CONFLICT","人工恢复确认已存在，请刷新核对");throw unavailable(failure);}
    }
    private static Confirmation confirmation(ResultSet r)throws SQLException{
        if(r.getLong("version")!=1||!"HUMAN_CONFIRMED".equals(r.getString("manual_recovery_status"))||!"SELF_REPORTED".equals(r.getString("verification_status"))||!"MANUAL_RECOVERY_CONFIRMATION".equals(r.getString("record_source"))||r.getObject("monitor_state")!=null||r.getObject("disposition_state")!=null||r.getBoolean("monitoring_recovery_applicable")||r.getBoolean("ticket_state_changed")||r.getBoolean("sla_changed"))throw corrupt();
        try{OffsetDateTime reported=r.getObject("reported_recovered_at",OffsetDateTime.class);ManualRecoveryConfirmation value=new ManualRecoveryConfirmation(r.getString("note"),reported==null?null:reported.toInstant());if(!value.noteSha256().equals(r.getString("note_sha256")))throw corrupt();return new Confirmation(uuid(r.getString("id")),uuid(r.getString("tenant_id")),uuid(r.getString("incident_id")),uuid(r.getString("actor_id")),value,r.getObject("recorded_at",OffsetDateTime.class).toInstant());}catch(RuntimeException malformed){throw corrupt();}
    }
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static boolean validUuid(String value){return value!=null&&value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");}
    private static String uuid(String value){if(!validUuid(value))throw invalid();return UUID.fromString(value).toString();}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员关系或权限版本已失效");}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","人工恢复确认对象、说明、时间或所见版本无效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException corrupt(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","人工恢复持久证据不一致，未返回未核验状态");}
    private static DomainException unavailable(SQLException failure){String state=failure.getSQLState();LOG.log(System.Logger.Level.WARNING,"Manual recovery PostgreSQL failure sql_state={0}",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN");return new DomainException(503,"UPSTREAM_UNAVAILABLE","人工恢复确认数据库或结构不可用，未取得成功证据");}
    @Override public List<String> readinessProblems(){
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);try(var s=statement(c,SELECT+" WHERE false")){s.executeQuery().close();}
            try(var s=statement(c,"SELECT current_database()='mop_test' AND current_user='mop_test_runtime' AND has_table_privilege(current_user,'public.mop_manual_recovery_confirmation','SELECT') AND has_table_privilege(current_user,'public.mop_manual_recovery_confirmation','INSERT') AND NOT has_table_privilege(current_user,'public.mop_manual_recovery_confirmation','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT has_any_column_privilege(current_user,'public.mop_manual_recovery_confirmation','UPDATE') AND has_table_privilege(current_user,'public.mop_manual_incident_registration','SELECT') AND NOT has_any_column_privilege(current_user,'public.mop_manual_incident_registration','UPDATE') AND NOT EXISTS(SELECT 1 FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid='public.mop_manual_recovery_confirmation'::regclass AND a.grantee=0)");var r=s.executeQuery()){if(!r.next()||!r.getBoolean(1))return List.of("MANUAL_RECOVERY_SCHEMA_OR_ACCESS_UNAVAILABLE");}return List.of();
        }catch(SQLException|RuntimeException failure){return List.of("MANUAL_RECOVERY_SCHEMA_OR_ACCESS_UNAVAILABLE");}
    }
}
