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
    @Override public Progress progress(Access a,String id){
        String canonical=uuid(id);
        return readonly(a,(c,role)->{
            Job job=read(c,a,role,canonical);
            // Only descriptors are selected: never materialize file bytes, object identity, lease owner or trace.
            String sql="SELECT version,fencing_token,error_code,(file_bytes IS NOT NULL) AS prepared,file_sha256,file_size,selection_sha256,row_count,generated_at,(SELECT count(*) FROM public.mop_asset_export_attempt h WHERE h.tenant_id=j.tenant_id AND h.job_id=j.id) AS history_count FROM public.mop_asset_export_job j WHERE tenant_id=?::uuid AND created_by=?::uuid AND id=?::uuid";
            try(var statement=statement(c,sql)){
                statement.setString(1,a.tenantId());statement.setString(2,a.actorId());statement.setString(3,canonical);
                try(var r=statement.executeQuery()){
                    if(!r.next())throw corrupt();
                    try{
                        int attempts=r.getInt("fencing_token");
                        if(r.getLong("version")!=job.version()||r.getLong("history_count")!=attempts)throw corrupt();
                        AssetExportJobState.Encoding prepared=null;
                        if(r.getBoolean("prepared")){
                            if(r.getObject("file_size")==null||r.getObject("row_count")==null)throw corrupt();
                            prepared=new AssetExportJobState.Encoding(r.getString("selection_sha256"),r.getString("file_sha256"),r.getLong("file_size"),r.getInt("row_count"),time(r,"generated_at"));
                        }else if(r.getString("file_sha256")!=null||r.getObject("file_size")!=null||r.getString("selection_sha256")!=null||r.getObject("row_count")!=null||r.getObject("generated_at")!=null)throw corrupt();
                        String error=r.getString("error_code");
                        return new Progress(job,attempts,prepared,error==null?null:AssetExportJobState.Failure.valueOf(error));
                    }catch(RuntimeException malformed){throw corrupt();}
                }
            }
        });
    }
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
    private record Locked(String id,AssetExportJobState.Command command,String trace,AssetExportJobState.State state,long version,int fence,String worker,Instant createdAt,Instant updatedAt,Instant leaseStarted,Instant leaseUntil,AssetExportJobState.Encoding encoding){}
    @Override public Work loadWork(Claim claim){
        validateClaim(claim);
        return platform.exportJobTransaction(()->{Connection c=platform.exportCommandConnection();try{Locked row=locked(c,claim);Instant now=checkLeaseAndAuthorization(c,row,claim);return work(c,row,now);}catch(SQLException failure){throw unavailable(failure);}});
    }
    @Override public Work prepare(Claim claim,AssetExportPreparedFile file){
        validateClaim(claim);if(file==null||!file.commandFingerprint().equals(claim.command().fingerprint()))throw invalid();
        return platform.exportJobTransaction(()->{
            Connection c=platform.exportCommandConnection();
            try{
                Locked row=locked(c,claim);Instant now=checkLeaseAndAuthorization(c,row,claim);
                if(row.encoding()!=null){Work retained=work(c,row,now);if(!retained.prepared().sameContent(file))throw new DomainException(409,"EXPORT_PREPARED_MISMATCH","Original prepared bytes and descriptor are immutable");return retained;}
                if(row.state()!=AssetExportJobState.State.RUNNING||row.version()!=claim.version())throw leaseLost();
                var e=file.encoding();if(e.generatedAt().isBefore(row.createdAt())||e.generatedAt().isAfter(now))throw invalid();
                if(row.version()>=9007199254740991L)throw new DomainException(409,"VERSION_CONFLICT","Export version reached its technical bound");
                try(var s=statement(c,"UPDATE public.mop_asset_export_job SET file_bytes=?,selection_sha256=?,row_count=?,generated_at=?,version=version+1,updated_at=? WHERE id=?::uuid AND version=? AND fencing_token=? AND lease_owner=?")){
                    s.setBytes(1,file.bytes());s.setString(2,e.selectionSha256());s.setInt(3,e.rowCount());instant(s,4,e.generatedAt());instant(s,5,now);s.setString(6,row.id());s.setLong(7,row.version());s.setInt(8,row.fence());s.setString(9,row.worker());if(s.executeUpdate()!=1)throw leaseLost();
                }
                var a=row.command().acceptedAccess();platform.audit(a.actorId().toString(),a.tenantId().toString(),"asset_export.prepare","asset_export",row.id(),"SUCCEEDED","PREPARED_BYTES_ONLY_NOT_STORED_OR_PUBLISHED",row.trace());
                return work(c,locked(c,claim),now);
            }catch(SQLException failure){if("23514".equals(failure.getSQLState())||"40001".equals(failure.getSQLState()))throw new DomainException(409,"EXPORT_STATE_CONFLICT","Export lease or retained facts changed during preparation");throw unavailable(failure);}
        });
    }
    private static void validateClaim(Claim claim){
        if(claim==null||claim.command()==null||claim.worker()==null||!claim.worker().matches("[A-Za-z0-9._:-]{1,64}")||claim.fencingToken()<1||claim.fencingToken()>AssetExportJobState.MAX_ATTEMPTS||claim.version()<1||claim.version()>9007199254740991L||claim.leaseUntil()==null)throw invalid();uuid(claim.jobId());
    }
    private static Locked locked(Connection c,Claim claim)throws SQLException{
        String columns=COLUMNS+",trace_id,fencing_token,lease_owner,lease_started_at,lease_until,(file_bytes IS NOT NULL) AS prepared,file_sha256,file_size,selection_sha256,row_count,generated_at";
        try(var s=statement(c,"SELECT "+columns+" FROM public.mop_asset_export_job WHERE id=?::uuid AND tenant_id=?::uuid AND created_by=?::uuid FOR UPDATE")){s.setString(1,claim.jobId());s.setString(2,claim.command().acceptedAccess().tenantId().toString());s.setString(3,claim.command().acceptedAccess().actorId().toString());try(var r=s.executeQuery()){
            if(!r.next())throw missing();
            try{
                var access=new AssetExportJobState.Access(UUID.fromString(r.getString("tenant_id")),UUID.fromString(r.getString("created_by")),r.getLong("permission_version"),r.getString("accepted_role"),true);var command=AssetExportCommandCodec.decodeBound(r.getBytes("command_bytes"),access,r.getString("input_sha256"));
                AssetExportJobState.Encoding encoding=null;
                if(r.getBoolean("prepared")){if(r.getObject("file_size")==null||r.getObject("row_count")==null)throw corrupt();encoding=new AssetExportJobState.Encoding(r.getString("selection_sha256"),r.getString("file_sha256"),r.getLong("file_size"),r.getInt("row_count"),time(r,"generated_at"));}
                else if(r.getObject("file_size")!=null||r.getObject("row_count")!=null||r.getString("file_sha256")!=null||r.getString("selection_sha256")!=null||r.getObject("generated_at")!=null)throw corrupt();
                Instant created=time(r,"created_at"),updated=time(r,"updated_at");long version=r.getLong("version");if(created==null||updated==null||updated.isBefore(created)||version<0||version>9007199254740991L||encoding!=null&&(encoding.generatedAt().isBefore(created)||encoding.generatedAt().isAfter(updated)))throw corrupt();
                return new Locked(r.getString("id"),command,r.getString("trace_id"),AssetExportJobState.State.valueOf(r.getString("state")),version,r.getInt("fencing_token"),r.getString("lease_owner"),created,updated,time(r,"lease_started_at"),time(r,"lease_until"),encoding);
            }catch(RuntimeException malformed){throw corrupt();}
        }}
    }
    private static Instant checkLeaseAndAuthorization(Connection c,Locked row,Claim claim)throws SQLException{
        var expected=row.command().acceptedAccess();var offered=claim.command().acceptedAccess();if(!expected.tenantId().equals(offered.tenantId())||!expected.actorId().equals(offered.actorId()))throw missing();
        if(!row.command().equals(claim.command())||row.fence()!=claim.fencingToken()||!Objects.equals(row.worker(),claim.worker())||!Objects.equals(row.leaseUntil(),claim.leaseUntil())||claim.version()>row.version()||row.leaseStarted()==null||row.leaseUntil()==null||row.state()!=AssetExportJobState.State.RUNNING&&row.state()!=AssetExportJobState.State.RECONCILING_STORAGE)throw leaseLost();
        String role=authorize(c,new Access(expected.actorId().toString(),expected.tenantId().toString(),expected.permissionVersion(),row.trace()),true);if(!role.equals(expected.role()))throw expired();
        Instant now=clock(c);if(now.isBefore(row.updatedAt())||now.isBefore(row.leaseStarted())||!now.isBefore(row.leaseUntil()))throw leaseLost();return now;
    }
    private static Work work(Connection c,Locked row,Instant now)throws SQLException{
        AssetExportPreparedFile file=null;
        if(row.encoding()!=null){
            // Fetch file contents only after scope, actual membership, worker/fence and lease checks.
            try(var s=statement(c,"SELECT file_bytes FROM public.mop_asset_export_job WHERE tenant_id=?::uuid AND id=?::uuid")){s.setString(1,row.command().acceptedAccess().tenantId().toString());s.setString(2,row.id());try(var r=s.executeQuery()){if(!r.next())throw corrupt();try{file=AssetExportPreparedFile.restore(row.command(),row.encoding(),r.getBytes(1));}catch(RuntimeException malformed){throw corrupt();}}}
        }
        return new Work(new Claim(row.id(),row.worker(),row.fence(),row.version(),row.leaseUntil(),row.command(),row.state()==AssetExportJobState.State.RECONCILING_STORAGE),now,file);
    }
    private static DomainException leaseLost(){return new DomainException(409,"LEASE_LOST","Current export worker, fence, version or lease no longer matches");}
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
