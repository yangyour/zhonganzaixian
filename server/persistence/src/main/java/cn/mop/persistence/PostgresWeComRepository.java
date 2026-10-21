package cn.mop.persistence;

import cn.mop.domain.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** PostgreSQL is the authority for bindings, one-use OAuth state and external-send outcomes. */
public final class PostgresWeComRepository {
    public record Access(String tenantId, String actorId, long permissionVersion, String traceId) {}
    public record Claim(String id, String tenantId, String actorId, String recipient, String text, String attemptId, boolean authorized) {}
    public record Login(String userId, String tenantId, long bindingVersion) {}
    private final DataSource source; private final PostgresPlatformRepository platform;
    public PostgresWeComRepository(DataSource source, PostgresPlatformRepository platform) { this.source=source; this.platform=platform; }

    public List<Map<String,Object>> bindings(Access a, String corp) {
        return read(c->{authorize(c,a,"users:read");return rows(c,"SELECT b.id,b.user_id,u.display_name,b.wecom_user_id,b.active,b.version,b.verified_at,b.updated_at FROM public.mop_wecom_binding b JOIN public.mop_user u ON u.id=b.user_id WHERE b.tenant_id=?::uuid AND b.corp_id=? ORDER BY b.created_at,b.id LIMIT 200",a.tenantId(),corp);});
    }
    /** Exact target lookup, independent of the bounded overview list. No provider access or writes. */
    public Map<String,Object> targetBinding(Access a,String user,String corp) {
        String target=uuid(user);
        return read(c->{authorize(c,a,"users:read");
            one(c,"SELECT user_id FROM public.mop_membership WHERE tenant_id=?::uuid AND user_id=?::uuid",a.tenantId(),target);
            var values=rows(c,"SELECT b.id,b.user_id,u.display_name,b.wecom_user_id,b.active,b.version,b.verified_at,b.updated_at FROM public.mop_wecom_binding b JOIN public.mop_user u ON u.id=b.user_id WHERE b.tenant_id=?::uuid AND b.user_id=?::uuid AND b.corp_id=?",a.tenantId(),target,corp);
            return values.isEmpty()?null:values.get(0);
        });
    }
    public void authorizeBinding(Access a, String user) {
        Connection c=platform.idempotentCommandConnection(); sql(c,db->{bindingScope(db,a,user);return null;});
    }
    private void bindingScope(Connection c,Access a,String user)throws SQLException {
        authorize(c,a,"users:write");
        one(c,"SELECT user_id FROM public.mop_membership WHERE tenant_id=?::uuid AND user_id=?::uuid AND active",a.tenantId(),uuid(user));
        for(var scope:rows(c,"SELECT tenant_id,role_code FROM public.mop_membership WHERE user_id=?::uuid",user)) {
            String tenant=scope.get("tenant_id").toString();
            var administrator=platform.membership(a.actorId(),tenant).filter(Membership::active).orElseThrow(PostgresWeComRepository::forbidden);
            var target=platform.membership(user,tenant).orElseThrow(PostgresWeComRepository::missing);
            if(!administrator.capabilities().contains("users:write") || !administrator.capabilities().contains("roles:write")
                && (!administrator.role().equals(target.role()) || !administrator.capabilities().containsAll(target.capabilities()))) throw forbidden();
        }
    }
    public Map<String,Object> bind(Access a,String user,String corp,String member,long expected,boolean active) {
        return sql(platform.idempotentCommandConnection(),c->{
            bindingScope(c,a,user);
            var old=rows(c,"SELECT * FROM public.mop_wecom_binding WHERE tenant_id=?::uuid AND user_id=?::uuid AND corp_id=? FOR UPDATE",a.tenantId(),user,corp);
            String id;
            if(old.isEmpty()) {
                if(expected!=0||!active)throw conflict();id=UUID.randomUUID().toString();
                execute(c,"INSERT INTO public.mop_wecom_binding(id,tenant_id,user_id,corp_id,wecom_user_id,verified_at,updated_by) VALUES(?::uuid,?::uuid,?::uuid,?,?,date_trunc('milliseconds',clock_timestamp()),?::uuid)",id,a.tenantId(),user,corp,member,a.actorId());
            }else {
                var row=old.get(0);id=row.get("id").toString();
                if(number(row,"version")!=expected || !member.equalsIgnoreCase(row.get("wecom_user_id").toString()))throw conflict();
                if(!Objects.equals(active,row.get("active"))) {
                    execute(c,"UPDATE public.mop_wecom_binding SET active=?,version=version+1,updated_by=?::uuid,updated_at=date_trunc('milliseconds',clock_timestamp()) WHERE id=?::uuid",active,a.actorId(),id);
                    execute(c,"UPDATE public.mop_identity_security SET revision=revision+1,updated_at=statement_timestamp() WHERE user_id=?::uuid",user);
                }
            }
            platform.audit(a.actorId(),a.tenantId(),"wecom.binding.change","user",user,"SUCCEEDED",active?"EXPLICIT_VERIFIED_MEMBER_BINDING":"BINDING_DISABLED_SESSIONS_REVOKED",a.traceId());
            return one(c,"SELECT id,user_id,wecom_user_id,active,version,verified_at,updated_at FROM public.mop_wecom_binding WHERE id=?::uuid",id);
        });
    }
    public void authorizeSend(Access a,String messageId) {
        sql(platform.idempotentCommandConnection(),c->{authorize(c,a,messageId==null?"users:write":"tickets:read");if(messageId!=null)ownMessage(c,a,messageId);return null;});
    }
    public Map<String,Object> enqueueTest(Access a,String corp,int agent,String recipient,String text) {
        return sql(platform.idempotentCommandConnection(),c->{authorize(c,a,"users:write");return enqueue(c,a,corp,agent,null,null,recipient,text,"TEST");});
    }
    public Map<String,Object> enqueueNotification(Access a,String corp,int agent,String messageId) {
        return sql(platform.idempotentCommandConnection(),c->{
            authorize(c,a,"tickets:read");var message=ownMessage(c,a,messageId);
            var binding=one(c,"SELECT id,wecom_user_id FROM public.mop_wecom_binding WHERE tenant_id=?::uuid AND user_id=?::uuid AND corp_id=? AND active FOR SHARE",a.tenantId(),a.actorId(),corp);
            var existing=rows(c,"SELECT id FROM public.mop_wecom_delivery WHERE tenant_id=?::uuid AND source_message_id=?::uuid AND binding_id=?::uuid",a.tenantId(),messageId,binding.get("id"));
            if(!existing.isEmpty())return delivery(c,a,existing.get(0).get("id").toString(),false);
            String text=bounded("【MOP 工单通知】"+message.get("title")+"\n"+message.get("body"));
            return enqueue(c,a,corp,agent,binding.get("id").toString(),messageId,binding.get("wecom_user_id").toString(),text,"NOTIFICATION");
        });
    }
    private Map<String,Object> ownMessage(Connection c,Access a,String id)throws SQLException {
        return one(c,"SELECT title,body FROM public.mop_notification_message WHERE tenant_id=?::uuid AND recipient_id=?::uuid AND id=?::uuid",a.tenantId(),a.actorId(),uuid(id));
    }
    private Map<String,Object> enqueue(Connection c,Access a,String corp,int agent,String binding,String message,String recipient,String text,String kind)throws SQLException {
        String id=UUID.randomUUID().toString();
        execute(c,"INSERT INTO public.mop_wecom_delivery(id,tenant_id,actor_id,permission_version,binding_id,source_message_id,corp_id,agent_id,recipient,message_text,kind) VALUES(?::uuid,?::uuid,?::uuid,?,?::uuid,?::uuid,?,?,?,?,?)",id,a.tenantId(),a.actorId(),a.permissionVersion(),binding,message,corp,agent,recipient,text,kind);
        platform.audit(a.actorId(),a.tenantId(),"wecom.message.enqueue","wecom_delivery",id,"SUCCEEDED","DURABLE_QUEUE_NOT_YET_SENT",a.traceId());
        return delivery(c,a,id,false);
    }
    public Map<String,Object> delivery(Access a,String id,boolean admin) {return read(c->{authorize(c,a,admin?"users:read":"tickets:read");return delivery(c,a,uuid(id),admin);});}
    private static final String DELIVERY_FIELDS="id,tenant_id,actor_id,source_message_id,recipient,kind,status,version,provider_message_id,error_code,provider_code,created_at,started_at,finished_at";
    private Map<String,Object> delivery(Connection c,Access a,String id,boolean admin)throws SQLException {return one(c,"SELECT "+DELIVERY_FIELDS+" FROM public.mop_wecom_delivery WHERE tenant_id=?::uuid AND id=?::uuid"+(admin?"":" AND actor_id=?::uuid"),admin?new Object[]{a.tenantId(),id}:new Object[]{a.tenantId(),id,a.actorId()});}
    public List<Map<String,Object>> deliveries(Access a,boolean admin) {return read(c->{authorize(c,a,admin?"users:read":"tickets:read");return rows(c,"SELECT "+DELIVERY_FIELDS+" FROM public.mop_wecom_delivery WHERE tenant_id=?::uuid"+(admin?"":" AND actor_id=?::uuid")+" ORDER BY created_at DESC,id DESC LIMIT 100",admin?new Object[]{a.tenantId()}:new Object[]{a.tenantId(),a.actorId()});});}
    public List<Map<String,Object>> deliveries(Access a,boolean admin,String corp,int agent) {return read(c->{authorize(c,a,admin?"users:read":"tickets:read");return rows(c,"SELECT "+DELIVERY_FIELDS+" FROM public.mop_wecom_delivery WHERE tenant_id=?::uuid AND corp_id=? AND agent_id=?"+(admin?"":" AND actor_id=?::uuid")+" ORDER BY created_at DESC,id DESC LIMIT 100",admin?new Object[]{a.tenantId(),corp,agent}:new Object[]{a.tenantId(),corp,agent,a.actorId()});});}
    public Map<String,Object> delivery(Access a,String id,boolean admin,String corp,int agent) {return read(c->{authorize(c,a,admin?"users:read":"tickets:read");return one(c,"SELECT "+DELIVERY_FIELDS+" FROM public.mop_wecom_delivery WHERE tenant_id=?::uuid AND id=?::uuid AND corp_id=? AND agent_id=?"+(admin?"":" AND actor_id=?::uuid"),admin?new Object[]{a.tenantId(),uuid(id),corp,agent}:new Object[]{a.tenantId(),uuid(id),corp,agent,a.actorId()});});}

    /** Claim commits before external I/O. Stale PROCESSING is UNKNOWN; it is never reclaimed for resend. */
    public Optional<Claim> claim(String tenant,String corp,int agent) {
        return tx(c->{
            var stale=rows(c,"SELECT id,actor_id FROM public.mop_wecom_delivery WHERE tenant_id=?::uuid AND status='PROCESSING' AND started_at<clock_timestamp()-interval '2 minutes' FOR UPDATE SKIP LOCKED",tenant);
            for(var row:stale){execute(c,"UPDATE public.mop_wecom_delivery SET status='UNKNOWN',version=2,error_code='PROCESS_INTERRUPTED_OUTCOME_UNKNOWN',finished_at=date_trunc('milliseconds',clock_timestamp()) WHERE id=?::uuid",row.get("id"));platform.audit(row.get("actor_id").toString(),tenant,"wecom.message.unknown","wecom_delivery",row.get("id").toString(),"UNKNOWN","NO_AUTOMATIC_RESEND",UUID.randomUUID().toString());}
            var values=rows(c,"SELECT * FROM public.mop_wecom_delivery WHERE tenant_id=?::uuid AND corp_id=? AND agent_id=? AND status='QUEUED' ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",tenant,corp,agent);
            if(values.isEmpty())return Optional.empty();var row=values.get(0);String id=row.get("id").toString(),actor=row.get("actor_id").toString(),attempt=UUID.randomUUID().toString();
            boolean allowed=true;
            try {PostgresAuthorization.require(c,actor,tenant,number(row,"permission_version"),"TEST".equals(row.get("kind"))?"users:write":"tickets:read");}
            catch(DomainException denied){allowed=false;}
            if(!rows(c,"SELECT id FROM public.mop_user WHERE id=?::uuid AND active",actor).isEmpty()){}else allowed=false;
            if(row.get("binding_id")!=null && rows(c,"SELECT id FROM public.mop_wecom_binding WHERE tenant_id=?::uuid AND id=?::uuid AND user_id=?::uuid AND active AND corp_id=? AND lower(wecom_user_id)=lower(?)",tenant,row.get("binding_id"),actor,corp,row.get("recipient")).isEmpty())allowed=false;
            execute(c,"UPDATE public.mop_wecom_delivery SET status='PROCESSING',version=1,attempt_id=?::uuid,started_at=date_trunc('milliseconds',clock_timestamp()) WHERE id=?::uuid",attempt,id);
            platform.audit(actor,tenant,"wecom.message.claim","wecom_delivery",id,"SUCCEEDED","EXTERNAL_ATTEMPT_RESERVED",attempt);
            return Optional.of(new Claim(id,tenant,actor,row.get("recipient").toString(),row.get("message_text").toString(),attempt,allowed));
        });
    }
    public void finish(Claim claim,String status,String providerId,String category,Integer code) {
        if(!Set.of("ACCEPTED","FAILED","UNKNOWN","CANCELLED").contains(status))throw new IllegalArgumentException();
        tx(c->{
            int n=execute(c,"UPDATE public.mop_wecom_delivery SET status=?,version=2,provider_message_id=?,error_code=?,provider_code=?,finished_at=date_trunc('milliseconds',clock_timestamp()) WHERE tenant_id=?::uuid AND id=?::uuid AND status='PROCESSING' AND version=1 AND attempt_id=?::uuid",status,providerId,category,code,claim.tenantId(),claim.id(),claim.attemptId());
            if(n!=1)throw conflict();
            platform.audit(claim.actorId(),claim.tenantId(),"wecom.message.result","wecom_delivery",claim.id(),status,"ACCEPTED".equals(status)?"PROVIDER_ACCEPTED_NOT_DELIVERED":category,claim.attemptId());return null;
        });
    }
    public void createLoginState(String stateHash,String browserHash,String tenant,String corp,int agent) {
        tx(c->{
            // Bound outstanding authorizations per project to prevent unbounded anonymous growth.
            try(var s=statement(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",tenant+"|wecom-login")){s.execute();}
            var count=one(c,"SELECT count(*) AS n FROM public.mop_wecom_login_state WHERE tenant_id=?::uuid AND expires_at>clock_timestamp() AND consumed_at IS NULL",tenant);
            if(number(count,"n")>=500)throw new DomainException(429,"WECOM_LOGIN_RATE_LIMITED","登录请求较多，请稍后重试");
            execute(c,"INSERT INTO public.mop_wecom_login_state(state_hash,browser_hash,tenant_id,corp_id,agent_id,created_at,expires_at) VALUES(?,?,?::uuid,?,?,date_trunc('milliseconds',statement_timestamp()),date_trunc('milliseconds',statement_timestamp())+interval '5 minutes')",stateHash,browserHash,tenant,corp,agent);return null;
        });
    }
    public boolean consumeLoginState(String stateHash,String browserHash,String tenant,String corp,int agent) {
        return tx(c->execute(c,"UPDATE public.mop_wecom_login_state SET consumed_at=date_trunc('milliseconds',clock_timestamp()) WHERE state_hash=? AND browser_hash=? AND tenant_id=?::uuid AND corp_id=? AND agent_id=? AND consumed_at IS NULL AND expires_at>clock_timestamp()",stateHash,browserHash,tenant,corp,agent)==1);
    }
    public Optional<Login> login(String tenant,String corp,String member) {
        return read(c->{var values=rows(c,"SELECT b.user_id,b.tenant_id,b.version FROM public.mop_wecom_binding b JOIN public.mop_user u ON u.id=b.user_id AND u.active JOIN public.mop_membership m ON m.tenant_id=b.tenant_id AND m.user_id=b.user_id AND m.active WHERE b.tenant_id=?::uuid AND b.corp_id=? AND lower(b.wecom_user_id)=lower(?) AND b.active",tenant,corp,member);return values.isEmpty()?Optional.empty():Optional.of(new Login(values.get(0).get("user_id").toString(),tenant,number(values.get(0),"version")));});
    }
    public List<String> readinessProblems() {
        try(Connection c=source.getConnection()){c.setReadOnly(true);WeComSchemaVerifier.verify(c);return List.of();}
        catch(Exception e){return List.of("WECOM_SCHEMA_OR_ACCESS_UNAVAILABLE");}
    }
    private void authorize(Connection c,Access a,String permission)throws SQLException {PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),permission);one(c,"SELECT id FROM public.mop_user WHERE id=?::uuid AND active",a.actorId());}
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T read(Work<T> work){try(Connection c=source.getConnection()){c.setReadOnly(true);return work.run(c);}catch(SQLException e){throw unavailable();}}
    private <T>T tx(Work<T> work){return platform.identityTransaction(c->sql(c,work));}
    private <T>T sql(Connection c,Work<T> work){try{return work.run(c);}catch(SQLException e){if("23505".equals(e.getSQLState()))throw new DomainException(409,"WECOM_BINDING_OR_DELIVERY_CONFLICT","该成员绑定或通知记录已存在，请刷新核对");throw unavailable();}}
    private static PreparedStatement statement(Connection c,String sql,Object...args)throws SQLException {var s=c.prepareStatement(sql);s.setQueryTimeout(10);for(int i=0;i<args.length;i++){Object v=args[i];if(v instanceof UUID)v=v.toString();s.setObject(i+1,v);}return s;}
    private static int execute(Connection c,String sql,Object...args)throws SQLException{try(var s=statement(c,sql,args)){return s.executeUpdate();}}
    private static List<Map<String,Object>> rows(Connection c,String sql,Object...args)throws SQLException{
        try(var s=statement(c,sql,args);var r=s.executeQuery()){var out=new ArrayList<Map<String,Object>>();var meta=r.getMetaData();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=meta.getColumnCount();i++){Object v=r.getObject(i);if(v instanceof UUID)v=v.toString();if(v instanceof Timestamp t)v=t.toInstant().toString();if(v instanceof OffsetDateTime t)v=t.toInstant().toString();row.put(meta.getColumnLabel(i),v);}out.add(row);}return out;}
    }
    private static Map<String,Object> one(Connection c,String sql,Object...args)throws SQLException{var values=rows(c,sql,args);if(values.isEmpty())throw missing();return values.get(0);}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static String uuid(String value){try{if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException();return value;}catch(RuntimeException e){throw new DomainException(400,"VALIDATION_ERROR","对象标识无效");}}
    private static String bounded(String value){StringBuilder result=new StringBuilder();int bytes=0;for(int cp:value.codePoints().toArray()){String next=new String(Character.toChars(cp));int n=next.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(bytes+n>2048)break;result.append(next);bytes+=n;}return result.toString();}
    private static DomainException missing(){return new DomainException(404,"NOT_FOUND","对象不存在或不在授权范围");}
    private static DomainException forbidden(){return new DomainException(403,"ACTION_FORBIDDEN","当前身份不能管理该用户的企业微信绑定");}
    private static DomainException conflict(){return new DomainException(409,"VERSION_CONFLICT","记录已变化，请刷新核对");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","企业微信持久化依赖暂不可用");}
}
