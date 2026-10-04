package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetexport.*;
import cn.mop.domain.assetexport.AssetExportJobRepository.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Durable accepted commands and original receipts. No public API, worker, store or download side effects. */
public final class PostgresAssetExportRepository implements AssetExportJobRepository {
    private static final System.Logger LOG=System.getLogger(PostgresAssetExportRepository.class.getName());
    private final DataSource source;private final PostgresPlatformRepository platform;
    private static final String COLUMNS="id,tenant_id,created_by,permission_version,accepted_role,command_bytes,input_sha256,state,version,created_at,updated_at";
    public PostgresAssetExportRepository(DataSource source,PostgresPlatformRepository platform){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);}
    @Override public void authorizeAcceptance(Access a){Connection c=platform.idempotentCommandConnection();trace(a);try{authorize(c,a,true);}catch(SQLException failure){throw unavailable(failure);}}
    @Override public Job accept(Access a,AssetExportJobState.Filters filters){
        Connection c=platform.idempotentCommandConnection();trace(a);
        try{
            String role=authorize(c,a,true);var command=new AssetExportJobState.Command(new AssetExportJobState.Access(UUID.fromString(a.tenantId()),UUID.fromString(a.actorId()),a.permissionVersion(),role,true),filters);
            String id=UUID.randomUUID().toString();
            try(var s=statement(c,"INSERT INTO public.mop_asset_export_job(id,tenant_id,created_by,permission_version,accepted_role,command_bytes,trace_id) VALUES (?::uuid,?::uuid,?::uuid,?,?,?,?)")){
                s.setString(1,id);s.setString(2,a.tenantId());s.setString(3,a.actorId());s.setLong(4,a.permissionVersion());s.setString(5,role);s.setBytes(6,command.canonicalBytes());s.setString(7,a.traceId());if(s.executeUpdate()!=1)throw corrupt();
            }
            platform.audit(a.actorId(),a.tenantId(),"asset_export.accept","asset_export",id,"SUCCEEDED","QUEUED_NOT_ENCODED_OR_PUBLISHED",a.traceId());
            return read(c,a,role,id);
        }catch(SQLException failure){throw unavailable(failure);}
    }
    @FunctionalInterface private interface Read<T>{T run(Connection c,String currentRole)throws SQLException;}
    private <T>T readonly(Access a,Read<T> action){
        platform.requireStandaloneRead();
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{String role=authorize(c,a,false);T result=action.run(c,role);c.commit();return result;}
            catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}
        }catch(SQLException failure){throw unavailable(failure);}
    }
    @Override public Job get(Access a,String id){String canonical=uuid(id);return readonly(a,(c,role)->read(c,a,role,canonical));}
    @Override public List<Job> list(Access a,int limit,Instant beforeCreatedAt,String beforeId){
        if(limit<1||limit>101||(beforeCreatedAt==null)!=(beforeId==null))throw invalid();String cursor=beforeId==null?null:uuid(beforeId);
        if(beforeCreatedAt!=null&&(beforeCreatedAt.getNano()%1000000!=0||beforeCreatedAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||beforeCreatedAt.isAfter(Instant.parse("9999-12-31T23:59:59.999Z"))))throw invalid();
        return readonly(a,(c,role)->{
            var ids=new ArrayList<String>();
            try(var s=statement(c,"SELECT id::text FROM public.mop_asset_export_job WHERE tenant_id=?::uuid AND created_by=?::uuid AND permission_version=? AND accepted_role=?"+(cursor==null?"":" AND (created_at,id)<(?::timestamptz,?::uuid)")+" ORDER BY created_at DESC,id DESC LIMIT ?")){
                int p=1;s.setString(p++,a.tenantId());s.setString(p++,a.actorId());s.setLong(p++,a.permissionVersion());s.setString(p++,role);if(cursor!=null){s.setObject(p++,OffsetDateTime.ofInstant(beforeCreatedAt,ZoneOffset.UTC));s.setString(p++,cursor);}s.setInt(p,limit);try(var r=s.executeQuery()){while(r.next())ids.add(r.getString(1));}
            }
            var result=new ArrayList<Job>();for(String id:ids)result.add(read(c,a,role,id));return List.copyOf(result);
        });
    }
    @Override public Optional<Claim> claim(String jobId,String worker,Duration lease){return claimMatching(uuid(jobId),worker,lease);}
    @Override public Optional<Claim> claimNext(String worker,Duration lease){return claimMatching(null,worker,lease);}
    private Optional<Claim> claimMatching(String target,String worker,Duration lease){
        if(worker==null||!worker.matches("[A-Za-z0-9._:-]{1,64}"))throw invalid();AssetExportJobState.validateLease(lease);
        return platform.exportJobTransaction(()->{
            Connection c=platform.exportCommandConnection();
            try{
                String id=null,tenant=null,actor=null,role=null,trace=null,hash=null;long permission=0,version=0;int fence=0;boolean prepared=false;byte[] bytes=null;
                try(var s=statement(c,"SELECT "+COLUMNS+",trace_id,fencing_token,(file_bytes IS NOT NULL) AS prepared FROM public.mop_asset_export_job WHERE (state='QUEUED' OR (state IN ('RUNNING','RECONCILING_STORAGE') AND lease_until<=clock_timestamp()))"+(target==null?" AND fencing_token<1000":" AND id=?::uuid")+" ORDER BY COALESCE(lease_until,created_at),created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED")){
                    if(target!=null)s.setString(1,target);
                    try(var r=s.executeQuery()){if(r.next()){id=r.getString("id");tenant=r.getString("tenant_id");actor=r.getString("created_by");role=r.getString("accepted_role");permission=r.getLong("permission_version");version=r.getLong("version");fence=r.getInt("fencing_token");prepared=r.getBoolean("prepared");trace=r.getString("trace_id");hash=r.getString("input_sha256");bytes=r.getBytes("command_bytes");}}
                }
                if(id==null)return Optional.empty();
                if(version>=9007199254740991L)throw new DomainException(409,"VERSION_CONFLICT","Export version reached its technical bound");
                var accepted=new AssetExportJobState.Access(UUID.fromString(tenant),UUID.fromString(actor),permission,role,true);AssetExportJobState.Command command;
                try{command=AssetExportCommandCodec.decodeBound(bytes,accepted,hash);}catch(RuntimeException malformed){throw corrupt();}
                // Lock actual current membership before choosing the new database clock/lease.
                boolean authorized=false;
                try(var s=statement(c,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active FOR SHARE OF m,u")){s.setString(1,tenant);s.setString(2,actor);try(var r=s.executeQuery()){authorized=r.next()&&r.getLong(2)==permission&&role.equals(r.getString(1));}}
                Instant now=clock(c);
                if(!authorized){
                    try(var s=statement(c,"UPDATE public.mop_asset_export_job SET state='FAILED',error_code='AUTHORIZATION_CHANGED',version=version+1,updated_at=? WHERE id=?::uuid AND version=?")){instant(s,1,now);s.setString(2,id);s.setLong(3,version);if(s.executeUpdate()!=1)throw corrupt();}
                    platform.audit(actor,tenant,"asset_export.authorization_changed","asset_export",id,"FAILED","CAPTURED_MEMBERSHIP_CHANGED_NOT_EXPORTED",trace);return Optional.empty();
                }
                if(fence>=AssetExportJobState.MAX_ATTEMPTS)throw new DomainException(409,"EXPORT_ATTEMPT_LIMIT","Technical export attempt history limit reached; no implicit retry policy selected");
                Instant until=now.plus(lease);if(until.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();
                String next=prepared?"RECONCILING_STORAGE":"RUNNING";
                try(var s=statement(c,"UPDATE public.mop_asset_export_job SET state=?,fencing_token=fencing_token+1,lease_owner=?,lease_started_at=?,lease_until=?,version=version+1,updated_at=? WHERE id=?::uuid AND version=?")){s.setString(1,next);s.setString(2,worker);instant(s,3,now);instant(s,4,until);instant(s,5,now);s.setString(6,id);s.setLong(7,version);if(s.executeUpdate()!=1)throw corrupt();}
                platform.audit(actor,tenant,"asset_export.claim","asset_export",id,"SUCCEEDED",prepared?"LEASE_CLAIMED_STORAGE_RECONCILIATION_REQUIRED":"LEASE_CLAIMED_NOT_ENCODED_OR_STORED",trace);
                return Optional.of(new Claim(id,worker,fence+1,version+1,until,command,prepared));
            }catch(SQLException failure){throw unavailable(failure);}
        });
    }
    private static Instant clock(Connection c)throws SQLException{try(var s=statement(c,"SELECT clock_timestamp()::timestamptz(3)");var r=s.executeQuery()){if(!r.next())throw corrupt();return r.getObject(1,OffsetDateTime.class).toInstant();}}
    private static void instant(PreparedStatement s,int index,Instant value)throws SQLException{s.setObject(index,OffsetDateTime.ofInstant(value,ZoneOffset.UTC));}
    private static Job read(Connection c,Access a,String currentRole,String id)throws SQLException{
        try(var s=statement(c,"SELECT "+COLUMNS+" FROM public.mop_asset_export_job WHERE tenant_id=?::uuid AND created_by=?::uuid AND id=?::uuid")){
            s.setString(1,a.tenantId());s.setString(2,a.actorId());s.setString(3,id);
            try(var r=s.executeQuery()){
                if(!r.next())throw missing();
                String tenant=r.getString("tenant_id"),actor=r.getString("created_by"),role=r.getString("accepted_role");long permission=r.getLong("permission_version");
                if(permission!=a.permissionVersion()||!Objects.equals(role,currentRole))throw expired();
                try{
                    var accepted=new AssetExportJobState.Access(UUID.fromString(tenant),UUID.fromString(actor),permission,role,true);String hash=r.getString("input_sha256");var command=AssetExportCommandCodec.decodeBound(r.getBytes("command_bytes"),accepted,hash);
                    var state=AssetExportJobState.State.valueOf(r.getString("state"));long version=r.getLong("version");Instant created=time(r,"created_at"),updated=time(r,"updated_at");
                    if(version<0||version>9007199254740991L||created==null||updated==null||updated.isBefore(created)||state==AssetExportJobState.State.QUEUED&&version!=0)throw corrupt();
                    return new Job(r.getString("id"),tenant,actor,permission,role,hash,command.filters(),state,version,created,updated);
                }catch(RuntimeException malformed){throw corrupt();}
            }
        }
    }
    private static String authorize(Connection c,Access a,boolean lock)throws SQLException{
        if(a==null||a.permissionVersion()<1||a.permissionVersion()>9007199254740991L)throw expired();uuid(a.actorId());uuid(a.tenantId());
        try(var s=statement(c,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""))){s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){if(!r.next()||r.getLong(2)!=a.permissionVersion())throw expired();String role=r.getString(1);if(!Set.of("padmin","msp").contains(role))throw new DomainException(403,"ACTION_FORBIDDEN","Current role cannot access internal ledger export jobs");return role;}}
    }
    private static void trace(Access a){if(a==null||a.traceId()==null||!a.traceId().matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static Instant time(ResultSet r,String column)throws SQLException{var value=r.getObject(column,OffsetDateTime.class);return value==null?null:value.toInstant();}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","Invalid export scope, trace or technical pagination");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","Current export membership or version changed");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","Export job not found");}
    private static DomainException corrupt(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","Stored export acceptance is inconsistent; no successful result returned");}
    private static DomainException unavailable(SQLException failure){String state=failure.getSQLState();LOG.log(System.Logger.Level.WARNING,"Export PostgreSQL failure sql_state={0}",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN");return new DomainException(503,"UPSTREAM_UNAVAILABLE","Export repository unavailable; acceptance not confirmed");}
    @Override public List<String> readinessProblems(){try(Connection c=source.getConnection()){c.setReadOnly(true);AssetExportSchemaVerifier.verify(c);return List.of();}catch(SQLException|RuntimeException failure){return List.of("ASSET_EXPORT_STRUCTURE_OR_GUARDS_UNAVAILABLE");}}
}
