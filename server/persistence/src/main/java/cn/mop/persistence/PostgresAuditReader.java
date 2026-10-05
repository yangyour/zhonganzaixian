package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.audit.AuditReader;
import cn.mop.persistence.audit.AuditChainVerifier;
import cn.mop.persistence.audit.AuditChainVerifier.Row;
import com.fasterxml.jackson.databind.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Authorized repeatable-read snapshots of the existing append-only V1 table. Never repairs a chain. */
public final class PostgresAuditReader implements AuditReader {
    private final DataSource dataSource;private final ObjectMapper mapper;
    private static final long MAX_SEQUENCE=9007199254740991L;
    private static final String ROWS="SELECT id::text,namespace,tenant_id::text,sequence,previous_hash,entry_hash,payload FROM public.mop_audit";
    public PostgresAuditReader(DataSource dataSource,ObjectMapper mapper){this.dataSource=Objects.requireNonNull(dataSource);this.mapper=Objects.requireNonNull(mapper);}
    @FunctionalInterface private interface Read<T>{T run(Connection connection)throws SQLException;}
    private <T>T read(Access access,Read<T> action){
        try(Connection c=dataSource.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{authorize(c,access);T value=action.run(c);c.commit();return value;}
            catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}
        }catch(SQLException failure){throw unavailable();}
    }
    @Override public Page list(Access access,Filters filters,int size,Long snapshot,Long before){
        return read(access,c->{
            validate(filters);if(!Set.of(10,15,25,50,100).contains(size)||snapshot!=null&&(snapshot<0||snapshot>MAX_SEQUENCE)||before!=null&&(snapshot==null||before<1||before>MAX_SEQUENCE))throw invalid();
            long tail=tail(c,access.tenantId()),upper=snapshot==null?tail:snapshot;if(upper>tail||before!=null&&before>upper)throw invalid();
            StringBuilder sql=new StringBuilder(ROWS+" WHERE tenant_id=?::uuid AND sequence<=?");List<Object> values=new ArrayList<>(List.of(access.tenantId(),upper));
            if(before!=null){sql.append(" AND sequence<?");values.add(before);}
            add(sql,values,"actor_id",filters.actorId());add(sql,values,"action",filters.action());add(sql,values,"result",filters.result());add(sql,values,"resource_type",filters.resourceType());add(sql,values,"resource_id",filters.resourceId());add(sql,values,"trace_id",filters.traceId());
            if(filters.occurredFrom()!=null){sql.append(" AND (payload->>'occurred_at')::timestamptz>=?");values.add(utc(filters.occurredFrom()));}
            if(filters.occurredBefore()!=null){sql.append(" AND (payload->>'occurred_at')::timestamptz<?");values.add(utc(filters.occurredBefore()));}
            sql.append(" ORDER BY sequence DESC LIMIT ?");values.add(size+1);List<Map<String,Object>> result=new ArrayList<>();
            try(var s=statement(c,sql.toString())){for(int i=0;i<values.size();i++)s.setObject(i+1,values.get(i));try(var r=s.executeQuery()){while(r.next()){
                Row row=row(r);JsonNode payload=row.payload();if(AuditChainVerifier.verifyLocalRecord(access.tenantId(),row)!=AuditChainVerifier.Failure.NONE)throw unavailable();
                result.add(Collections.unmodifiableMap(mapper.convertValue(payload,LinkedHashMap.class)));
            }}}
            boolean more=result.size()>size;if(more)result.remove(result.size()-1);Long next=more?((Number)result.get(result.size()-1).get("sequence")).longValue():null;return new Page(List.copyOf(result),upper,next,more);
        });
    }
    @Override public Integrity verify(Access access,long from,Long requestedTo){
        return read(access,c->{
            if(from<1||from>MAX_SEQUENCE||requestedTo!=null&&(requestedTo<from||requestedTo>MAX_SEQUENCE||requestedTo-from>=AuditChainVerifier.MAX_RANGE))throw invalid();
            long tail=tail(c,access.tenantId());Instant checked;
            try(var s=statement(c,"SELECT transaction_timestamp()" );var r=s.executeQuery()){r.next();checked=r.getObject(1,OffsetDateTime.class).toInstant();}
            if(tail==0&&from==1&&requestedTo==null)return new Integrity("EMPTY",null,0,null,null,0,null,"NONE",null,null,false,null,checked);
            if(from>tail||requestedTo!=null&&requestedTo>tail)throw new DomainException(404,"AUDIT_RANGE_NOT_FOUND","所选审计区间在当前授权快照中不存在");
            long to=requestedTo==null?Math.min(tail,from+AuditChainVerifier.MAX_RANGE-1):requestedTo;
            List<Row> previous=from==1?List.of():range(c,access.tenantId(),from-1,from-1);Row anchor=previous.size()==1?previous.get(0):null;
            var result=AuditChainVerifier.verify(access.tenantId(),from,to,anchor,range(c,access.tenantId(),from,to));boolean more=to<tail;
            return new Integrity(result.valid()?"VALID":"INVALID",result.valid(),tail,from,to,result.verifiedRecords(),result.failureSequence(),result.failure().name(),result.anchorHash(),result.finalHash(),more,result.valid()&&more?to+1:null,checked);
        });
    }
    private static void add(StringBuilder sql,List<Object> values,String field,String value){if(value!=null){sql.append(" AND payload->>'").append(field).append("'=?");values.add(value);}}
    private List<Row> range(Connection c,String tenant,long from,long to)throws SQLException{List<Row> values=new ArrayList<>();try(var s=statement(c,ROWS+" WHERE tenant_id=?::uuid AND sequence>=? AND sequence<=? ORDER BY sequence")){s.setString(1,tenant);s.setLong(2,from);s.setLong(3,to);try(var r=s.executeQuery()){while(r.next())values.add(row(r));}}return values;}
    private Row row(ResultSet r)throws SQLException{try{return new Row(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5),r.getString(6),mapper.readTree(r.getString(7)));}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw unavailable();}}
    private static long tail(Connection c,String tenant)throws SQLException{try(var s=statement(c,"SELECT COALESCE(max(sequence),0) FROM public.mop_audit WHERE tenant_id=?::uuid")){s.setString(1,tenant);try(var r=s.executeQuery()){r.next();long value=r.getLong(1);if(value<0||value>MAX_SEQUENCE)throw unavailable();return value;}}}
    private static void authorize(Connection c,Access access)throws SQLException{
        if(access==null)throw new DomainException(401,"SESSION_EXPIRED","当前授权上下文无效");PostgresAuthorization.require(c,access.actorId(),access.tenantId(),access.permissionVersion(),"audit:read");
        if(access==null||access.actorId()==null||access.tenantId()==null||access.permissionVersion()<1)throw expired();
        try(var s=statement(c,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id::text=? AND m.user_id::text=? AND m.active AND u.active")){
            s.setString(1,access.tenantId());s.setString(2,access.actorId());try(var r=s.executeQuery()){if(!r.next()||r.getLong(2)!=access.permissionVersion())throw expired();if(!"padmin".equals(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","当前身份无本租户审计查询权限");}
        }
    }
    private static void validate(Filters filters){
        if(filters==null)throw invalid();if(filters.actorId()!=null&&!filters.actorId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid();
        text(filters.action(),200);text(filters.result(),32);text(filters.resourceType(),64);text(filters.resourceId(),160);text(filters.traceId(),128);
        if(filters.occurredFrom()!=null&&filters.occurredBefore()!=null&&!filters.occurredBefore().isAfter(filters.occurredFrom()))throw invalid();
        for(Instant instant:new Instant[]{filters.occurredFrom(),filters.occurredBefore()})if(instant!=null&&(instant.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||instant.isAfter(Instant.parse("9999-12-31T23:59:59.999Z"))||instant.getNano()%1_000_000!=0))throw invalid();
    }
    private static void text(String value,int max){if(value!=null&&(value.isEmpty()||value.codePointCount(0,value.length())>max||value.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)||value.codePoints().anyMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.SURROGATE)))throw invalid();}
    private static OffsetDateTime utc(Instant instant){return OffsetDateTime.ofInstant(instant,ZoneOffset.UTC);}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员关系或权限版本已失效");}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","审计筛选或有界区间无效");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","审计来源未就绪或记录格式不兼容；没有返回成功校验结论");}
}
