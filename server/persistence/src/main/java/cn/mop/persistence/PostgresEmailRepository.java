package cn.mop.persistence;

import cn.mop.domain.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Durable explicit email actions. Claims commit before any external SMTP submission. */
public final class PostgresEmailRepository {
    public record Access(String tenantId, String actorId, long permissionVersion, String traceId) {}
    public record Claim(String id, String tenantId, String actorId, String sender, String recipient,
                        String subject, String text, String attemptId, boolean authorized) {}
    private final DataSource source; private final PostgresPlatformRepository platform;
    private static final String FIELDS = "id,tenant_id,actor_id,source_message_id,sender,recipient,kind,status,version,message_id,error_code,smtp_code,created_at,started_at,finished_at";
    public PostgresEmailRepository(DataSource source, PostgresPlatformRepository platform) { this.source=source; this.platform=platform; }
    public List<Map<String,Object>> bindings(Access a,String sender) {
        return read(c->{ authorize(c,a,"users:read"); return rows(c,"SELECT b.id,b.user_id,u.display_name,b.email,b.active,b.version,b.updated_at FROM public.mop_email_binding b JOIN public.mop_user u ON u.id=b.user_id WHERE b.tenant_id=?::uuid AND b.sender=? ORDER BY b.created_at,b.id LIMIT 200",a.tenantId(),sender); });
    }
    public Map<String,Object> ownBinding(Access a,String sender) {
        return read(c->{ authorize(c,a,"tickets:read"); var values=rows(c,"SELECT id,email,active,version FROM public.mop_email_binding WHERE tenant_id=?::uuid AND user_id=?::uuid AND sender=?",a.tenantId(),a.actorId(),sender); return values.isEmpty()?null:values.get(0); });
    }
    public void authorizeBinding(Access a,String user) { sql(platform.idempotentCommandConnection(),c->{bindingScope(c,a,user);return null;}); }
    private void bindingScope(Connection c,Access a,String user)throws SQLException {
        authorize(c,a,"users:write"); one(c,"SELECT user_id FROM public.mop_membership WHERE tenant_id=?::uuid AND user_id=?::uuid AND active",a.tenantId(),uuid(user));
        for(var scope:rows(c,"SELECT tenant_id FROM public.mop_membership WHERE user_id=?::uuid",user)) {
            String tenant=scope.get("tenant_id").toString();
            var administrator=platform.membership(a.actorId(),tenant).filter(Membership::active).orElseThrow(PostgresEmailRepository::forbidden);
            var target=platform.membership(user,tenant).orElseThrow(PostgresEmailRepository::missing);
            if(!administrator.capabilities().contains("users:write") || !administrator.capabilities().contains("roles:write")
                && (!administrator.role().equals(target.role()) || !administrator.capabilities().containsAll(target.capabilities()))) throw forbidden();
        }
    }
    public Map<String,Object> bind(Access a,String user,String sender,String email,long expected,boolean active) {
        return sql(platform.idempotentCommandConnection(),c->{
            bindingScope(c,a,user);
            var values=rows(c,"SELECT * FROM public.mop_email_binding WHERE tenant_id=?::uuid AND user_id=?::uuid AND sender=? FOR UPDATE",a.tenantId(),user,sender);
            String id;
            if(values.isEmpty()) {
                if(expected!=0||!active)throw conflict(); id=UUID.randomUUID().toString();
                execute(c,"INSERT INTO public.mop_email_binding(id,tenant_id,user_id,sender,email,updated_by) VALUES(?::uuid,?::uuid,?::uuid,?,?,?::uuid)",id,a.tenantId(),user,sender,email,a.actorId());
            } else {
                var old=values.get(0);id=old.get("id").toString();if(number(old,"version")!=expected)throw conflict();
                if(!Objects.equals(old.get("active"),active)||!Objects.equals(old.get("email"),email))
                    execute(c,"UPDATE public.mop_email_binding SET email=?,active=?,version=version+1,updated_by=?::uuid,updated_at=date_trunc('milliseconds',clock_timestamp()) WHERE id=?::uuid",email,active,a.actorId(),id);
            }
            platform.audit(a.actorId(),a.tenantId(),"email.binding.change","user",user,"SUCCEEDED","ADMIN_CONFIGURED_ADDRESS_NOT_OWNERSHIP_PROOF",a.traceId());
            return one(c,"SELECT id,user_id,email,active,version,updated_at FROM public.mop_email_binding WHERE id=?::uuid",id);
        });
    }
    public void authorizeSend(Access a,String sourceId) { sql(platform.idempotentCommandConnection(),c->{authorize(c,a,sourceId==null?"users:write":"tickets:read");if(sourceId!=null)ownMessage(c,a,sourceId);return null;}); }
    public Map<String,Object> enqueueTest(Access a,String sender,String recipient,String subject,String text) {
        return sql(platform.idempotentCommandConnection(),c->{authorize(c,a,"users:write");return enqueue(c,a,sender,recipient,subject,text,null,null,null,"TEST");});
    }
    public Map<String,Object> enqueueNotification(Access a,String sender,String sourceId) {
        return sql(platform.idempotentCommandConnection(),c->{
            authorize(c,a,"tickets:read");var message=ownMessage(c,a,sourceId);
            var binding=one(c,"SELECT id,email,version FROM public.mop_email_binding WHERE tenant_id=?::uuid AND user_id=?::uuid AND sender=? AND active FOR SHARE",a.tenantId(),a.actorId(),sender);
            var old=rows(c,"SELECT id FROM public.mop_email_delivery WHERE tenant_id=?::uuid AND source_message_id=?::uuid AND binding_id=?::uuid",a.tenantId(),sourceId,binding.get("id"));
            if(!old.isEmpty())return delivery(c,a,old.get(0).get("id").toString(),false,sender);
            String subject=("【MOP 工单通知】"+message.get("title")).replaceAll("[\\p{Cntrl}]"," ");
            if(subject.length()>200)subject=subject.substring(0,200);
            return enqueue(c,a,sender,binding.get("email").toString(),subject,message.get("body").toString(),binding.get("id").toString(),number(binding,"version"),sourceId,"NOTIFICATION");
        });
    }
    private Map<String,Object> ownMessage(Connection c,Access a,String id)throws SQLException { return one(c,"SELECT title,body FROM public.mop_notification_message WHERE tenant_id=?::uuid AND recipient_id=?::uuid AND id=?::uuid",a.tenantId(),a.actorId(),uuid(id)); }
    private Map<String,Object> enqueue(Connection c,Access a,String sender,String recipient,String subject,String text,String binding,Long bindingVersion,String sourceId,String kind)throws SQLException {
        String id=UUID.randomUUID().toString();
        execute(c,"INSERT INTO public.mop_email_delivery(id,tenant_id,actor_id,permission_version,binding_id,binding_version,source_message_id,sender,recipient,subject,message_text,kind) VALUES(?::uuid,?::uuid,?::uuid,?,?::uuid,?,?::uuid,?,?,?,?,?)",id,a.tenantId(),a.actorId(),a.permissionVersion(),binding,bindingVersion,sourceId,sender,recipient,subject,text,kind);
        platform.audit(a.actorId(),a.tenantId(),"email.message.enqueue","email_delivery",id,"SUCCEEDED","DURABLE_QUEUE_NOT_YET_SENT",a.traceId()); return delivery(c,a,id,false,sender);
    }
    public Map<String,Object> delivery(Access a,String id,boolean admin,String sender) { return read(c->{authorize(c,a,admin?"users:read":"tickets:read");return delivery(c,a,uuid(id),admin,sender);}); }
    private Map<String,Object> delivery(Connection c,Access a,String id,boolean admin,String sender)throws SQLException { return one(c,"SELECT "+FIELDS+" FROM public.mop_email_delivery WHERE tenant_id=?::uuid AND id=?::uuid AND sender=?"+(admin?"":" AND actor_id=?::uuid"),admin?new Object[]{a.tenantId(),id,sender}:new Object[]{a.tenantId(),id,sender,a.actorId()}); }
    public List<Map<String,Object>> deliveries(Access a,boolean admin,String sender) { return read(c->{authorize(c,a,admin?"users:read":"tickets:read");return rows(c,"SELECT "+FIELDS+" FROM public.mop_email_delivery WHERE tenant_id=?::uuid AND sender=?"+(admin?"":" AND actor_id=?::uuid")+" ORDER BY created_at DESC,id DESC LIMIT 100",admin?new Object[]{a.tenantId(),sender}:new Object[]{a.tenantId(),sender,a.actorId()});}); }
    public Optional<Claim> claim(String tenant,String sender) {
        return tx(c->{
            for(var stale:rows(c,"SELECT id,actor_id FROM public.mop_email_delivery WHERE tenant_id=?::uuid AND sender=? AND status='PROCESSING' AND started_at<clock_timestamp()-interval '2 minutes' FOR UPDATE SKIP LOCKED",tenant,sender)) {
                execute(c,"UPDATE public.mop_email_delivery SET status='UNKNOWN',version=2,error_code='PROCESS_INTERRUPTED_OUTCOME_UNKNOWN',finished_at=date_trunc('milliseconds',clock_timestamp()) WHERE id=?::uuid",stale.get("id"));
                platform.audit(stale.get("actor_id").toString(),tenant,"email.message.unknown","email_delivery",stale.get("id").toString(),"UNKNOWN","NO_AUTOMATIC_RESEND",UUID.randomUUID().toString());
            }
            var values=rows(c,"SELECT * FROM public.mop_email_delivery WHERE tenant_id=?::uuid AND sender=? AND status='QUEUED' ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",tenant,sender);
            if(values.isEmpty())return Optional.empty();var row=values.get(0);String id=row.get("id").toString(),actor=row.get("actor_id").toString(),attempt=UUID.randomUUID().toString();
            boolean allowed=true;
            try { PostgresAuthorization.require(c,actor,tenant,number(row,"permission_version"),"TEST".equals(row.get("kind"))?"users:write":"tickets:read"); }
            catch(DomainException denied) { allowed=false; }
            if(rows(c,"SELECT id FROM public.mop_user WHERE id=?::uuid AND active",actor).isEmpty())allowed=false;
            if(row.get("binding_id")!=null && rows(c,"SELECT id FROM public.mop_email_binding WHERE tenant_id=?::uuid AND id=?::uuid AND user_id=?::uuid AND sender=? AND email=? AND version=? AND active",tenant,row.get("binding_id"),actor,sender,row.get("recipient"),row.get("binding_version")).isEmpty())allowed=false;
            execute(c,"UPDATE public.mop_email_delivery SET status='PROCESSING',version=1,attempt_id=?::uuid,started_at=date_trunc('milliseconds',clock_timestamp()) WHERE id=?::uuid",attempt,id);
            platform.audit(actor,tenant,"email.message.claim","email_delivery",id,"SUCCEEDED","EXTERNAL_ATTEMPT_RESERVED",attempt);
            return Optional.of(new Claim(id,tenant,actor,sender,row.get("recipient").toString(),row.get("subject").toString(),row.get("message_text").toString(),attempt,allowed));
        });
    }
    public void finish(Claim claim,String status,String messageId,String error,Integer code) {
        if(!Set.of("ACCEPTED","FAILED","UNKNOWN","CANCELLED").contains(status))throw new IllegalArgumentException();
        tx(c->{
            int changed=execute(c,"UPDATE public.mop_email_delivery SET status=?,version=2,message_id=?,error_code=?,smtp_code=?,finished_at=date_trunc('milliseconds',clock_timestamp()) WHERE tenant_id=?::uuid AND id=?::uuid AND sender=? AND status='PROCESSING' AND version=1 AND attempt_id=?::uuid",status,messageId,error,code,claim.tenantId(),claim.id(),claim.sender(),claim.attemptId());
            if(changed!=1)throw conflict();platform.audit(claim.actorId(),claim.tenantId(),"email.message.result","email_delivery",claim.id(),status,"ACCEPTED".equals(status)?"SMTP_ACCEPTED_NOT_DELIVERED":error,claim.attemptId());return null;
        });
    }
    public List<String> readinessProblems() { try(Connection c=source.getConnection()){c.setReadOnly(true);EmailSchemaVerifier.verify(c);return List.of();}catch(Exception failure){return List.of("EMAIL_SCHEMA_OR_ACCESS_UNAVAILABLE");} }
    private void authorize(Connection c,Access a,String permission)throws SQLException {PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),permission);one(c,"SELECT id FROM public.mop_user WHERE id=?::uuid AND active",a.actorId());}
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T read(Work<T> work){try(Connection c=source.getConnection()){c.setReadOnly(true);return work.run(c);}catch(SQLException failure){throw unavailable();}}
    private <T>T tx(Work<T> work){return platform.identityTransaction(c->sql(c,work));}
    private <T>T sql(Connection c,Work<T> work){try{return work.run(c);}catch(SQLException failure){if("23505".equals(failure.getSQLState()))throw new DomainException(409,"EMAIL_BINDING_OR_DELIVERY_CONFLICT","邮箱绑定或发送记录已存在，请刷新核对");throw unavailable();}}
    private static PreparedStatement statement(Connection c,String query,Object...args)throws SQLException {var s=c.prepareStatement(query);s.setQueryTimeout(10);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;}
    private static int execute(Connection c,String query,Object...args)throws SQLException {try(var s=statement(c,query,args)){return s.executeUpdate();}}
    private static List<Map<String,Object>> rows(Connection c,String query,Object...args)throws SQLException {
        try(var s=statement(c,query,args);var result=s.executeQuery()){var out=new ArrayList<Map<String,Object>>();var meta=result.getMetaData();while(result.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=meta.getColumnCount();i++){Object v=result.getObject(i);if(v instanceof UUID)v=v.toString();if(v instanceof Timestamp t)v=t.toInstant().toString();if(v instanceof OffsetDateTime t)v=t.toInstant().toString();row.put(meta.getColumnLabel(i),v);}out.add(row);}return out;}
    }
    private static Map<String,Object> one(Connection c,String query,Object...args)throws SQLException {var values=rows(c,query,args);if(values.isEmpty())throw missing();return values.get(0);}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static String uuid(String value){try{if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException();return value;}catch(RuntimeException failure){throw new DomainException(400,"VALIDATION_ERROR","对象标识无效");}}
    private static DomainException missing(){return new DomainException(404,"NOT_FOUND","对象不存在或不在授权范围");}
    private static DomainException forbidden(){return new DomainException(403,"ACTION_FORBIDDEN","当前身份不能管理该用户的邮箱绑定");}
    private static DomainException conflict(){return new DomainException(409,"VERSION_CONFLICT","记录已变化，请刷新核对");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","邮件持久化依赖暂不可用");}
}
