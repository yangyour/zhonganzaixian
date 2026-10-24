package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.DeliveryJournalReader;
import cn.mop.domain.notification.DeliveryReceiptState.*;
import cn.mop.persistence.DeliveryReceiptJournal.Command;
import cn.mop.persistence.DeliveryReceiptSnapshotCodec.Scope;
import cn.mop.persistence.PostgresDeliveryReceiptRepository.Access;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Explicit local failed-declaration disposition, audit and original reply share one transaction. Never sends/retries. */
public final class DeliveryDeadLetterService {
    private final PostgresPlatformRepository platform;
    private final PostgresDeliveryReceiptRepository journal;
    private final boolean enabled;
    private final Function<UUID,Set<UUID>> readPools,writePools;
    public DeliveryDeadLetterService(DataSource source,PostgresPlatformRepository platform,boolean enabled,
                                     Function<UUID,Set<UUID>> readPools,Function<UUID,Set<UUID>> writePools){
        this.platform=Objects.requireNonNull(platform);this.enabled=enabled;this.readPools=Objects.requireNonNull(readPools);this.writePools=Objects.requireNonNull(writePools);
        journal=new PostgresDeliveryReceiptRepository(source,(c,a,s,op)->{
            if(op!=PostgresDeliveryReceiptRepository.Operation.DEAD_LETTER)throw forbidden();
            var actual=authorize(c,a,s.deliveryId());if(!actual.equals(s))throw missing();
        },(c,a,s,command)->{if(!(command.transition() instanceof DeadLettered))throw forbidden();});
    }
    public Object dispose(Access access,UUID delivery,long expected,UUID command,String reason,String trace){
        validate(access,delivery,expected,command,reason,trace);
        if(!enabled)throw new DomainException(503,"DELIVERY_DEAD_LETTER_DISABLED","人工死信处置尚未显式启用");
        String route="POST /api/v1/notification-deliveries/"+delivery+"/dead-letter";
        String fingerprint=hash(delivery+"\n"+expected+"\n"+reason);
        return platform.idempotentAuthorized(access.actorId().toString(),access.tenantId().toString(),route,command.toString(),fingerprint,access.permissionVersion(),
            ()->{try{authorize(owned(),access,delivery);}catch(SQLException e){throw unavailable();}},
            ()->{try{
                var c=owned();var scope=authorize(c,access,delivery);var at=now(c);
                var receipt=journal.deadLetterInTransaction(c,access,scope,new Command(expected,command,access.actorId(),access.permissionVersion(),trace,new DeadLettered(at)));
                if(receipt.appliedVersion()!=expected+1||receipt.journal().state().status()!=Status.DEAD_LETTER||receipt.replay())throw unavailable();
                platform.auditInTransaction(c,access.actorId().toString(),access.tenantId().toString(),"notification.delivery.dead_letter","notification_delivery",delivery.toString(),reason,trace);
                authorize(c,access,delivery);
                var result=new LinkedHashMap<String,Object>();result.put("tenant_id",access.tenantId().toString());result.put("actor_id",access.actorId().toString());
                result.put("delivery_id",delivery.toString());result.put("resource_pool_id",scope.key().resourcePoolId().toString());result.put("command_id",command.toString());
                result.put("applied_version",receipt.appliedVersion());result.put("recorded_status_at_application","DEAD_LETTER");result.put("reason",reason);result.put("applied_at",at.toString());
                result.put("source","LOCAL_FAILED_DECLARATION");result.put("provider_receipt_verified",false);result.put("automatic_send_enabled",false);result.put("fresh_send_authorized",false);
                result.put("receipt_scope","ORIGINAL_COMMAND_NOT_CURRENT_HEAD");return result;
            }catch(SQLException e){throw unavailable();}});
    }
    private Connection owned(){var c=platform.existingCommandConnection();if(c==null)throw new IllegalStateException("Disposition requires owned idempotent transaction");return c;}
    private Scope authorize(Connection c,Access a,UUID delivery)throws SQLException{
        if(!enabled)throw new DomainException(503,"DELIVERY_DEAD_LETTER_DISABLED","人工死信处置尚未显式启用");
        PostgresDeliveryJournalReader.runtime(c);
        PostgresDeliveryJournalReader.authority(c,new DeliveryJournalReader.Access(a.tenantId(),a.actorId(),a.permissionVersion()));
        PostgresAuthorization.require(c,a.actorId().toString(),a.tenantId().toString(),a.permissionVersion(),"configurations:write");
        var read=configured(readPools,a.tenantId());var write=configured(writePools,a.tenantId());DeliveryReceiptSchemaVerifier.verify(c);
        try(var q=query(c,"SELECT resource_pool_id,event_id,channel,receiver_id,stage FROM public.mop_delivery_receipt_journal WHERE tenant_id=? AND delivery_id=? AND version=0")){
            q.setObject(1,a.tenantId());q.setObject(2,delivery);try(var r=q.executeQuery()){
                if(!r.next())throw missing();UUID pool=r.getObject(1,UUID.class);if(!read.contains(pool)||!write.contains(pool))throw missing();
                var key=new Deduplication(pool,r.getObject(2,UUID.class),Channel.valueOf(r.getString(3)),r.getObject(4,UUID.class),r.getString(5));
                return new Scope(a.tenantId(),delivery,key);
            }
        }
    }
    private static Set<UUID> configured(Function<UUID,Set<UUID>> values,UUID tenant){
        var pools=values.apply(tenant);if(pools==null||pools.isEmpty())throw new DomainException(503,"DELIVERY_DISPOSITION_SCOPE_PENDING","人工处置需要独立明确的资源池读写名单");
        if(pools.size()>100||pools.stream().anyMatch(Objects::isNull))throw unavailable();return Set.copyOf(pools);
    }
    private static void validate(Access a,UUID id,long expected,UUID command,String reason,String trace){
        if(a==null||id==null||command==null||expected<0||expected>=12000||reason==null||reason.isBlank()||reason.length()>1000
            ||reason.codePoints().anyMatch(ch->ch<32&&ch!=10&&ch!=13&&ch!=9)||trace==null||!trace.matches("[A-Za-z0-9._:-]{1,128}"))throw new DomainException(400,"VALIDATION_ERROR","人工处置需要明确投递、当前版本、原命令及原因");
    }
    private static Instant now(Connection c)throws SQLException{try(var q=query(c,"SELECT date_trunc('milliseconds',clock_timestamp())");var r=q.executeQuery()){if(!r.next())throw unavailable();return r.getObject(1,OffsetDateTime.class).toInstant();}}
    private static PreparedStatement query(Connection c,String sql)throws SQLException{var q=c.prepareStatement(sql);q.setQueryTimeout(5);return q;}
    private static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static DomainException forbidden(){return new DomainException(403,"DELIVERY_DISPOSITION_FORBIDDEN","当前操作不能生成供应商请求或回执");}
    private static DomainException missing(){return new DomainException(404,"NOT_FOUND","当前授权范围未找到投递记录");}
    private static DomainException unavailable(){return new DomainException(503,"DELIVERY_DISPOSITION_UNAVAILABLE","人工处置未能核对，请保留原命令；没有外发或重试");}
}
