package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.DeliveryReceiptState;
import cn.mop.domain.notification.DeliveryReceiptState.*;
import cn.mop.persistence.DeliveryReceiptJournal.Command;
import cn.mop.persistence.DeliveryReceiptSnapshotCodec.Scope;
import javax.sql.DataSource;
import java.io.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Explicitly constructed V50 repository. No bean, scheduler, provider IO, credential or default authority. */
public final class PostgresDeliveryReceiptRepository {
    private static final long MAX=9_007_199_254_740_991L;
    private static final String TABLE="public.mop_delivery_receipt_journal";
    public record Access(UUID tenantId,UUID actorId,long permissionVersion){public Access{if(tenantId==null||actorId==null||permissionVersion<1||permissionVersion>MAX)throw invalid();}}
    public enum Operation{READ,QUEUE,START,DEAD_LETTER,RECORD_RECEIPT}
    /** Check live scope/action authority and hold revocation locks. Must not commit, send, or perform external IO. */
    @FunctionalInterface public interface Authorizer{void authorize(Connection c,Access access,Scope scope,Operation operation)throws SQLException;}
    /** Verify trusted original request/receipt correlation or dead-letter/start prerequisites. No reference-only proof. */
    @FunctionalInterface public interface CommandVerifier{void verify(Connection c,Access access,Scope scope,Command command)throws SQLException;}
    public record Receipt(DeliveryReceiptJournal journal,long appliedVersion,boolean replay){
        public boolean databaseRecorded(){return true;}public boolean providerReceiptVerified(){return false;}public boolean automaticSendEnabled(){return false;}
    }
    /** Original stored command plus current head; recovery is never a fresh permission to send. */
    public record RecoveredCommand(Command original, long appliedVersion, DeliveryReceiptJournal current) {}

    /** Recovers a transition after a lost response using its original identity and live read authorization. */
    public Optional<RecoveredCommand> recover(Access access, Scope scope, UUID commandId) {
        matching(access, scope);
        if (commandId == null) {
            throw invalid();
        }
        return transaction(access, scope, Operation.READ, connection -> {
            var loaded = load(connection, scope, false);
            if (loaded == null) {
                return Optional.empty();
            }
            var journal = loaded.journal();
            return journal.command(commandId).map(command -> new RecoveredCommand(command,
                    journal.commandVersion(commandId).orElseThrow(), journal));
        });
    }
    private record Row(Scope scope,long version,UUID commandId,UUID actorId,long permissionVersion,String trace,long expectedVersion,String previous,byte[] encoded,String sha,String commandSha,Instant recordedAt){}
    private record Loaded(DeliveryReceiptJournal journal,Row root,List<Row> rows){}
    private final DataSource source;private final Authorizer authorizer;private final CommandVerifier verifier;
    public PostgresDeliveryReceiptRepository(DataSource source,Authorizer authorizer,CommandVerifier verifier){this.source=Objects.requireNonNull(source);this.authorizer=Objects.requireNonNull(authorizer);this.verifier=Objects.requireNonNull(verifier);}

    public Receipt queue(Access access,Scope scope,UUID commandId,Instant queuedAt,String trace){
        matching(access,scope);if(commandId==null||!trace(trace))throw invalid();var initial=DeliveryReceiptJournal.queued(scope,queuedAt);
        String fingerprint=fingerprint(initial.root().sha256(),commandId,access.actorId(),access.permissionVersion(),trace,-1,null);
        return transaction(access,scope,Operation.QUEUE,c->{
            var old=load(c,scope,true);if(old!=null){if(!old.root().commandId().equals(commandId)||!old.root().commandSha().equals(fingerprint)||!old.journal().root().sha256().equals(initial.root().sha256()))throw conflict();return new Receipt(old.journal(),0,true);}
            rejectOtherCommand(c,access.tenantId(),commandId);
            insert(c,scope,0,commandId,access.actorId(),access.permissionVersion(),trace,-1,null,initial.root().bytes(),initial.root().sha256(),fingerprint);
            var stored=required(load(c,scope,true));if(!stored.journal().headSha256().equals(initial.headSha256()))throw unavailable();return new Receipt(stored.journal(),0,false);
        });
    }
    public Optional<Receipt> current(Access access,Scope scope){matching(access,scope);return transaction(access,scope,Operation.READ,c->{var loaded=load(c,scope,false);return loaded==null?Optional.empty():Optional.of(new Receipt(loaded.journal(),loaded.journal().state().version(),false));});}
    public Receipt append(Access access,Scope scope,Command command){
        matching(access,scope);if(command==null||!command.actorId().equals(access.actorId())||command.permissionVersion()!=access.permissionVersion())throw invalid();
        Operation operation=command.transition() instanceof Started?Operation.START:command.transition() instanceof DeadLettered?Operation.DEAD_LETTER:Operation.RECORD_RECEIPT;
        return transaction(access,scope,operation,c->{
            var old=required(load(c,scope,true));
            // Both original and replay require present authorization and trusted prerequisites.
            try{verifier.verify(c,access,scope,command);}catch(SQLException|RuntimeException rejected){throw new DomainException(503,"DELIVERY_JOURNAL_PREREQUISITE_UNAVAILABLE","通知回执前置核验未完成；没有派发请求");}
            var result=old.journal().append(command);if(result.replay())return new Receipt(result.journal(),result.appliedVersion(),true);
            rejectOtherCommand(c,access.tenantId(),command.commandId());var entry=result.journal().entries().get(result.journal().entries().size()-1);
            String fingerprint=fingerprint(entry.sha256(),command.commandId(),command.actorId(),command.permissionVersion(),command.traceId(),command.expectedVersion(),old.journal().headSha256());
            insert(c,scope,result.appliedVersion(),command.commandId(),command.actorId(),command.permissionVersion(),command.traceId(),command.expectedVersion(),old.journal().headSha256(),entry.bytes(),entry.sha256(),fingerprint);
            var stored=required(load(c,scope,true));if(!stored.journal().headSha256().equals(result.journal().headSha256()))throw unavailable();return new Receipt(stored.journal(),result.appliedVersion(),false);
        });
    }
    /** Borrowed read only; caller must hold current authorization and scope locks in the same transaction. */
    record ReadJournal(DeliveryReceiptJournal journal,UUID queueCommandId,Instant firstRecordedAt,Map<UUID,Instant> commandRecordedAt){ReadJournal{commandRecordedAt=Map.copyOf(commandRecordedAt);}}
    static ReadJournal readVerifiedInTransaction(Connection c,Scope scope)throws SQLException{
        if(c.getAutoCommit())throw unavailable();var loaded=required(load(c,scope,false));var times=new HashMap<UUID,Instant>();for(var row:loaded.rows())if(times.put(row.commandId(),row.recordedAt())!=null)throw unavailable();
        return new ReadJournal(loaded.journal(),loaded.root().commandId(),loaded.root().recordedAt(),times);
    }
    private static Loaded load(Connection c,Scope scope,boolean write)throws SQLException{
        try(var q=query(c,"SELECT 1 FROM "+TABLE+" WHERE tenant_id=? AND delivery_id=? AND version=0 "+(write?"FOR UPDATE":"FOR SHARE"))){q.setObject(1,scope.tenantId());q.setObject(2,scope.deliveryId());try(var r=q.executeQuery()){if(!r.next())return null;}}
        var rows=new ArrayList<Row>();
        try(var q=query(c,"SELECT * FROM "+TABLE+" WHERE tenant_id=? AND delivery_id=? ORDER BY version LIMIT 12002")){q.setObject(1,scope.tenantId());q.setObject(2,scope.deliveryId());try(var r=q.executeQuery()){while(r.next())rows.add(row(r));}}
        if(rows.isEmpty()||rows.size()>DeliveryReceiptJournal.MAX_ENTRIES+1)throw unavailable();var root=rows.get(0);
        if(!scope.equals(root.scope()))throw new DomainException(404,"NOT_FOUND","Delivery journal not found");
        if(root.version()!=0||root.expectedVersion()!=-1||root.previous()!=null)throw unavailable();
        var entries=new ArrayList<DeliveryReceiptJournal.Entry>();String head=root.sha();long version=0;
        for(var row:rows){
            if(!scope.equals(row.scope())||row.version()!=version||row.expectedVersion()!=version-1)throw unavailable();
            if(!row.commandSha().equals(fingerprint(row.sha(),row.commandId(),row.actorId(),row.permissionVersion(),row.trace(),row.expectedVersion(),row.previous())))throw unavailable();
            if(version>0){if(!head.equals(row.previous()))throw unavailable();entries.add(new DeliveryReceiptJournal.Entry(row.encoded(),row.sha()));head=row.sha();}
            version++;
        }
        DeliveryReceiptJournal journal;
        try{journal=DeliveryReceiptJournal.restore(root.encoded(),root.sha(),scope,entries,rows.size()-1,head);}catch(RuntimeException rejected){throw unavailable();}
        if(journal.command(root.commandId()).isPresent())throw unavailable();
        for(int i=1;i<rows.size();i++){var row=rows.get(i);var command=journal.command(row.commandId()).orElseThrow(PostgresDeliveryReceiptRepository::unavailable);
            if(journal.commandVersion(row.commandId()).orElse(-1)!=row.version()||!command.actorId().equals(row.actorId())||command.permissionVersion()!=row.permissionVersion()||!command.traceId().equals(row.trace())||command.expectedVersion()!=row.expectedVersion())throw unavailable();}
        return new Loaded(journal,root,List.copyOf(rows));
    }
    private static Row row(ResultSet r)throws SQLException{
        try{
            var scope=new Scope(r.getObject("tenant_id",UUID.class),r.getObject("delivery_id",UUID.class),new Deduplication(r.getObject("resource_pool_id",UUID.class),r.getObject("event_id",UUID.class),Channel.valueOf(r.getString("channel")),r.getObject("receiver_id",UUID.class),r.getString("stage")));
            UUID command=r.getObject("command_id",UUID.class),actor=r.getObject("actor_id",UUID.class);long permission=r.getLong("permission_version");String trace=r.getString("trace_id");byte[] encoded=r.getBytes("encoded_record");String sha=r.getString("record_sha256"),fingerprint=r.getString("command_sha256");var recorded=r.getObject("recorded_at",OffsetDateTime.class);
            if(command==null||actor==null||permission<1||permission>MAX||!trace(trace)||encoded==null||encoded.length<1||encoded.length>1024||sha==null||!sha.matches("[0-9a-f]{64}")||fingerprint==null||!fingerprint.matches("[0-9a-f]{64}")||recorded==null||r.getLong("root_version")!=0||!"INTERNAL_DELIVERY_RECEIPT_JOURNAL".equals(r.getString("record_source"))||r.getBoolean("provider_receipt_verified")||r.getBoolean("automatic_send_enabled"))throw unavailable();
            return new Row(scope,r.getLong("version"),command,actor,permission,trace,r.getLong("expected_version"),r.getString("previous_sha256"),encoded,sha,fingerprint,recorded.toInstant());
        }catch(RuntimeException rejected){throw unavailable();}
    }
    private static void insert(Connection c,Scope scope,long version,UUID command,UUID actor,long permission,String trace,long expected,String previous,byte[] encoded,String sha,String fingerprint)throws SQLException{
        try(var q=query(c,"INSERT INTO "+TABLE+" (tenant_id,delivery_id,version,resource_pool_id,event_id,channel,receiver_id,stage,command_id,actor_id,permission_version,trace_id,expected_version,previous_sha256,encoded_record,record_sha256,command_sha256) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")){
            int i=1;q.setObject(i++,scope.tenantId());q.setObject(i++,scope.deliveryId());q.setLong(i++,version);q.setObject(i++,scope.key().resourcePoolId());q.setObject(i++,scope.key().eventId());q.setString(i++,scope.key().channel().name());q.setObject(i++,scope.key().receiverId());q.setString(i++,scope.key().stage());q.setObject(i++,command);q.setObject(i++,actor);q.setLong(i++,permission);q.setString(i++,trace);q.setLong(i++,expected);q.setString(i++,previous);q.setBytes(i++,encoded);q.setString(i++,sha);q.setString(i,fingerprint);if(q.executeUpdate()!=1)throw unavailable();
        }
    }
    private static void rejectOtherCommand(Connection c,UUID tenant,UUID command)throws SQLException{try(var q=query(c,"SELECT 1 FROM "+TABLE+" WHERE tenant_id=? AND command_id=?")){q.setObject(1,tenant);q.setObject(2,command);try(var r=q.executeQuery()){if(r.next())throw conflict();}}}
    private static Loaded required(Loaded value){if(value==null)throw new DomainException(404,"NOT_FOUND","Delivery journal not found");return value;}
    private static PreparedStatement query(Connection c,String sql)throws SQLException{var q=c.prepareStatement(sql);q.setQueryTimeout(5);return q;}
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T transaction(Access access,Scope scope,Operation operation,Work<T> work){
        try(var c=source.getConnection()){
            c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try{authorize(c,access,scope,operation);T result=work.run(c);authorize(c,access,scope,operation);c.commit();return result;}
            catch(SQLException|RuntimeException failed){try{c.rollback();}catch(SQLException ignored){}throw failed;}
        }catch(DomainException rejected){throw safe(rejected);}catch(SQLException rejected){if("23505".equals(rejected.getSQLState()))throw conflict();throw unavailable();}catch(RuntimeException rejected){throw unavailable();}
    }
    private void authorize(Connection c,Access access,Scope scope,Operation operation)throws SQLException{
        try{authorizer.authorize(c,access,scope,operation);}catch(DomainException rejected){throw new DomainException(rejected.status==404?404:403,"DELIVERY_JOURNAL_ACCESS_DENIED","当前身份无权访问此通知回执日志");}catch(RuntimeException rejected){throw unavailable();}
    }
    private static DomainException safe(DomainException e){return switch(e.code){case "VERSION_CONFLICT","DELIVERY_JOURNAL_CONFLICT","DELIVERY_STATE_CONFLICT"->conflict();case "DELIVERY_JOURNAL_ACCESS_DENIED"->new DomainException(e.status,e.code,"当前身份无权访问此通知回执日志");case "NOT_FOUND"->new DomainException(404,"NOT_FOUND","当前范围未找到通知回执日志");case "VALIDATION_ERROR"->invalid();case "DELIVERY_JOURNAL_PREREQUISITE_UNAVAILABLE"->new DomainException(503,e.code,"通知回执前置核验未完成；没有派发请求");default->unavailable();};}
    private static void matching(Access a,Scope s){if(a==null||s==null)throw invalid();if(!a.tenantId().equals(s.tenantId()))throw new DomainException(404,"NOT_FOUND","Delivery journal not found");}
    private static boolean trace(String value){return value!=null&&value.matches("[A-Za-z0-9._:-]{1,128}");}
    private static String fingerprint(String record,UUID command,UUID actor,long permission,String trace,long expected,String previous){
        try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){out.writeUTF("MOP_DELIVERY_JOURNAL_COMMAND_V1");out.writeUTF(record);out.writeUTF(command.toString());out.writeUTF(actor.toString());out.writeLong(permission);out.writeUTF(trace);out.writeLong(expected);out.writeBoolean(previous!=null);if(previous!=null)out.writeUTF(previous);out.flush();return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));}catch(IOException|NoSuchAlgorithmException rejected){throw unavailable();}
    }
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","通知回执日志范围或命令格式无效");}
    private static DomainException conflict(){return new DomainException(409,"DELIVERY_JOURNAL_CONFLICT","通知回执日志版本或原命令冲突；未派发请求");}
    private static DomainException unavailable(){return new DomainException(503,"DELIVERY_JOURNAL_UNAVAILABLE","通知回执日志暂不可用或完整性核验失败；未派发请求");}
}
