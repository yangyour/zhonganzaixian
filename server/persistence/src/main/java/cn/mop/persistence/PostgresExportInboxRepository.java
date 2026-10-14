package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetexport.*;
import cn.mop.domain.assetexport.AssetExportJobRepository.Access;
import cn.mop.domain.assetexport.AssetExportPublicationRepository.Publication;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class PostgresExportInboxRepository implements ExportInboxRepository {
    private final DataSource source;private final PostgresPlatformRepository platform;
    private static final String SELECT="SELECT m.*,j.file_size,j.row_count FROM public.mop_export_inbox m JOIN public.mop_asset_export_job j ON j.id=m.job_id AND j.tenant_id=m.tenant_id ";
    private static final String SCOPE="m.tenant_id=?::uuid AND m.recipient_id=?::uuid AND m.permission_version=? AND j.created_by=m.recipient_id AND j.permission_version=m.permission_version AND j.accepted_role=? AND j.state='SUCCEEDED' AND j.version=m.published_version AND j.file_sha256=m.file_sha256 AND j.stored_at=m.issued_at";
    public PostgresExportInboxRepository(DataSource source,PostgresPlatformRepository platform){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);}
    @Override public void recordPublished(Access a,Publication p){
        Connection c=platform.idempotentCommandConnection();
        if(p==null||!p.published()||!p.job().tenantId().equals(a.tenantId())||!p.job().createdBy().equals(a.actorId())||p.job().permissionVersion()!=a.permissionVersion())throw invalid();
        try{authorize(c,a);try(var s=statement(c,"INSERT INTO public.mop_export_inbox(job_id,tenant_id,recipient_id,permission_version,published_version,file_sha256,issued_at,expires_at) VALUES (?::uuid,?::uuid,?::uuid,?,?,?,?,?)")){
            s.setString(1,p.job().id());s.setString(2,a.tenantId());s.setString(3,a.actorId());s.setLong(4,a.permissionVersion());s.setLong(5,p.job().version());s.setString(6,p.prepared().fileSha256());time(s,7,p.storedAt());time(s,8,p.storedAt().plusSeconds(900));s.executeUpdate();
        }platform.audit(a.actorId(),a.tenantId(),"export_inbox.store","asset_export",p.job().id(),"SUCCEEDED","OWN_INBOX_AVAILABLE_NOT_READ_OR_EXTERNAL_DELIVERY",a.traceId());}catch(SQLException e){throw unavailable();}
    }
    @Override public Page list(Access a,int limit,Instant before,String beforeId,boolean unread){
        if(limit<1||limit>50||(before==null)!=(beforeId==null)||before!=null&&(before.getNano()%1000000!=0||before.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||before.isAfter(Instant.parse("9999-12-31T23:59:59.999Z"))))throw invalid();if(beforeId!=null)uuid(beforeId);
        platform.requireStandaloneRead();
        try(var c=source.getConnection()){c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{String role=authorize(c,a);long count;
                try(var s=statement(c,"SELECT count(*) FROM public.mop_export_inbox m JOIN public.mop_asset_export_job j ON j.id=m.job_id AND j.tenant_id=m.tenant_id WHERE "+SCOPE+" AND m.read_at IS NULL")){scope(s,a,role);try(var r=s.executeQuery()){r.next();count=r.getLong(1);}}
                var items=new ArrayList<Message>();String sql=SELECT+"WHERE "+SCOPE+(unread?" AND m.read_at IS NULL":"")+(before==null?"":" AND (m.issued_at,m.job_id)<(?,?::uuid)")+" ORDER BY m.issued_at DESC,m.job_id DESC LIMIT ?";
                try(var s=statement(c,sql)){scope(s,a,role);int i=5;if(before!=null){time(s,i++,before);s.setString(i++,beforeId);}s.setInt(i,limit+1);try(var r=s.executeQuery()){while(r.next())items.add(message(r));}}
                boolean more=items.size()>limit;if(more)items.remove(items.size()-1);Instant now=now(c);c.commit();return new Page(items,more,count,now);
            }catch(SQLException|RuntimeException|Error e){c.rollback();throw e;}
        }catch(SQLException e){throw unavailable();}
    }
    @Override public void authorizeRead(Access a,String id){Connection c=platform.idempotentCommandConnection();try{read(c,a,authorize(c,a),uuid(id),false);}catch(SQLException e){throw unavailable();}}
    @Override public Message markRead(Access a,String id,long expected){
        if(expected<0||expected>1)throw invalid();Connection c=platform.idempotentCommandConnection();
        try{String role=authorize(c,a);Message old=read(c,a,role,uuid(id),true);if(old.version()!=expected)throw new DomainException(409,"VERSION_CONFLICT","消息已读状态已变化，请刷新后核对");if(old.readAt()!=null)return old;
            try(var s=statement(c,"UPDATE public.mop_export_inbox SET version=1,read_at=date_trunc('milliseconds',clock_timestamp()) WHERE job_id=?::uuid AND version=0")){s.setString(1,id);if(s.executeUpdate()!=1)throw new DomainException(409,"VERSION_CONFLICT","消息状态已变化");}
            platform.audit(a.actorId(),a.tenantId(),"export_inbox.read","asset_export",id,"SUCCEEDED","EXPLICIT_FIRST_READ_NO_DOWNLOAD_RENEWAL",a.traceId());return read(c,a,role,id,false);
        }catch(SQLException e){throw unavailable();}
    }
    private static Message read(Connection c,Access a,String role,String id,boolean lock)throws SQLException{try(var s=statement(c,SELECT+"WHERE "+SCOPE+" AND m.job_id=?::uuid"+(lock?" FOR UPDATE OF m":""))){scope(s,a,role);s.setString(5,id);try(var r=s.executeQuery()){if(!r.next())throw new DomainException(404,"OBJECT_NOT_FOUND","当前范围没有这条导出消息");return message(r);}}}
    private static Message message(ResultSet r)throws SQLException{return new Message(r.getString("job_id"),r.getString("tenant_id"),r.getString("recipient_id"),r.getLong("permission_version"),r.getLong("published_version"),r.getString("file_sha256"),r.getLong("file_size"),r.getInt("row_count"),instant(r,"issued_at"),instant(r,"expires_at"),r.getLong("version"),instant(r,"read_at"));}
    private static void scope(PreparedStatement s,Access a,String role)throws SQLException{s.setString(1,a.tenantId());s.setString(2,a.actorId());s.setLong(3,a.permissionVersion());s.setString(4,role);}
    private static String authorize(Connection c,Access a)throws SQLException{if(a==null)throw new DomainException(401,"SESSION_EXPIRED","会话已失效");PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),"assets:read");try(var s=statement(c,"SELECT role_code FROM public.mop_membership WHERE tenant_id=?::uuid AND user_id=?::uuid AND active AND permission_version=?")){s.setString(1,a.tenantId());s.setString(2,a.actorId());s.setLong(3,a.permissionVersion());try(var r=s.executeQuery()){if(!r.next())throw new DomainException(401,"SESSION_EXPIRED","成员授权已失效");String role=r.getString(1);if(!Set.of("padmin","msp").contains(role))throw new DomainException(403,"ACTION_FORBIDDEN","导出消息仅限获授权内部成员");return role;}}}
    private static String uuid(String id){try{if(id==null||!UUID.fromString(id).toString().equals(id))throw invalid();return id;}catch(IllegalArgumentException e){throw invalid();}}
    private static Instant instant(ResultSet r,String key)throws SQLException{var t=r.getObject(key,OffsetDateTime.class);return t==null?null:t.toInstant();}
    private static void time(PreparedStatement s,int i,Instant t)throws SQLException{s.setObject(i,t.atOffset(ZoneOffset.UTC));}
    private static Instant now(Connection c)throws SQLException{try(var s=statement(c,"SELECT clock_timestamp()::timestamptz(3)");var r=s.executeQuery()){r.next();return r.getObject(1,OffsetDateTime.class).toInstant();}}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    @Override public List<String> readinessProblems(){try(var c=source.getConnection()){c.setReadOnly(true);ExportInboxSchemaVerifier.verify(c);return List.of();}catch(SQLException|RuntimeException e){return List.of("EXPORT_INBOX_STRUCTURE_OR_GUARDS_UNAVAILABLE");}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","导出消息范围、版本或分页参数无效");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","导出消息库或保护约束未就绪，写入结果未确认");}
}
