package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.*;
import cn.mop.domain.notification.DeliveryJournalReader.*;
import cn.mop.domain.notification.DeliveryReceiptState.*;
import cn.mop.persistence.DeliveryReceiptSnapshotCodec.Scope;
import cn.mop.persistence.PostgresDeliveryReceiptRepository.ReadJournal;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** SELECT-only business footprint, current-scope share locks and complete bounded journal replay. */
public final class PostgresDeliveryJournalReader implements DeliveryJournalReader {
 private static final long PAGE_REVISION_BUDGET=24002;
 /** Existing restricted runtime connection source, independently verified for every read. */
 private final DataSource source;
 /** Server-controlled per-tenant resource pools; absent configuration is never an empty success. */
 private final Function<UUID,Set<UUID>> pools;
 public PostgresDeliveryJournalReader(DataSource source,Function<UUID,Set<UUID>> pools){this.source=Objects.requireNonNull(source);this.pools=Objects.requireNonNull(pools);}
 @FunctionalInterface private interface Work<T>{T run(Connection c,Set<UUID> scope,Instant observed)throws SQLException;}
 private <T>T read(Access a,Work<T> work){Objects.requireNonNull(a);try(var c=source.getConnection()){
  c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
  try{runtime(c);authority(c,a);var allowed=configured(a.tenantId());DeliveryReceiptSchemaVerifier.verify(c);var at=now(c);T result=work.run(c,allowed,at);
   authority(c,a);if(!allowed.equals(configured(a.tenantId())))throw new DomainException(401,"SESSION_EXPIRED","投递日志读取范围已变化");c.commit();return result;
  }catch(SQLException|RuntimeException|Error e){try{c.rollback();}catch(SQLException ignored){}throw e;}
 }catch(DomainException e){throw e;}catch(Exception e){throw unavailable();}}
 @Override public Page list(Access a,UUID pool,Cursor before,int size){if(size<1||size>20)throw invalid();return read(a,(c,allowed,at)->{
  if(pool!=null&&!allowed.contains(pool))throw missing();var selected=pool==null?allowed:Set.of(pool);var values=selected.stream().map(UUID::toString).sorted().toArray();long total;
  try(var q=query(c,"SELECT count(*) FROM public.mop_delivery_receipt_journal WHERE tenant_id=? AND version=0 AND resource_pool_id=ANY(?::uuid[])")){q.setObject(1,a.tenantId());var array=c.createArrayOf("uuid",values);try{q.setArray(2,array);try(var r=q.executeQuery()){if(!r.next())throw unavailable();total=r.getLong(1);}}finally{array.free();}}
  var roots=new ArrayList<Root>();try(var q=query(c,"SELECT tenant_id,delivery_id,resource_pool_id,event_id,channel,receiver_id,stage,recorded_at FROM public.mop_delivery_receipt_journal WHERE tenant_id=? AND version=0 AND resource_pool_id=ANY(?::uuid[])"+(before==null?"":" AND (recorded_at,delivery_id)<(?,?)")+" ORDER BY recorded_at DESC,delivery_id DESC LIMIT ?")){
   q.setObject(1,a.tenantId());var array=c.createArrayOf("uuid",values);try{q.setArray(2,array);int i=3;if(before!=null){time(q,i++,before.firstRecordedAt());q.setObject(i++,before.deliveryId());}q.setInt(i,size+1);try(var r=q.executeQuery()){while(r.next())roots.add(root(r));}}finally{array.free();}
  }
  if(before==null&&roots.size()!=Math.min(total,size+1L))throw unavailable();var entries=new ArrayList<Entry>();long budget=PAGE_REVISION_BUDGET;
  for(var root:roots.subList(0,Math.min(roots.size(),size))){bound(a,allowed,root);if(before!=null&&(root.recordedAt().isAfter(before.firstRecordedAt())||root.recordedAt().equals(before.firstRecordedAt())&&root.scope().deliveryId().toString().compareTo(before.deliveryId().toString())>=0))throw unavailable();budget-=revisionCount(c,root.scope());if(budget<0)throw limit();var journal=PostgresDeliveryReceiptRepository.readVerifiedInTransaction(c,root.scope());if(!root.recordedAt().equals(journal.firstRecordedAt()))throw unavailable();entries.add(entry(journal));}
  Cursor next=null;if(roots.size()>size){var last=entries.get(entries.size()-1);next=new Cursor(last.firstRecordedAt(),last.deliveryId());}return new Page(a,at,size,total,entries,next);
 });}
    /** Complete bounded replay precedes status filtering, count and pagination in the same repeatable-read transaction. */
    @Override
    public AttentionPage attention(
        Access access,
        UUID pool,
        AttentionStatus status,
        Cursor before,
        int size
    ) {
        if (size < 1 || size > 20 || status == null) {
            throw invalid();
        }
        return read(access, (connection, allowed, observed) -> {
            if (pool != null && !allowed.contains(pool)) {
                throw missing();
            }
            var selected = pool == null ? allowed : Set.of(pool);
            var roots = new ArrayList<Root>();
            try (
                var statement = query(
                    connection,
                    "SELECT tenant_id,delivery_id,resource_pool_id,event_id,channel,receiver_id,stage,recorded_at " +
                        "FROM public.mop_delivery_receipt_journal WHERE tenant_id=? AND version=0 " +
                        "AND resource_pool_id=ANY(?::uuid[]) ORDER BY recorded_at DESC,delivery_id DESC LIMIT 1001"
                )
            ) {
                statement.setObject(1, access.tenantId());
                var poolsArray = connection.createArrayOf("uuid", selected.toArray());
                try {
                    statement.setArray(2, poolsArray);
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            roots.add(root(rows));
                        }
                    }
                } finally {
                    poolsArray.free();
                }
            }
            if (roots.size() > 1000) {
                throw new DomainException(
                    422,
                    "DELIVERY_ATTENTION_SCAN_LIMIT",
                    "待核对读取超过1000条根记录技术预算，请缩小资源池范围"
                );
            }
            var matches = new ArrayList<Entry>();
            long budget = PAGE_REVISION_BUDGET;
            for (var root : roots) {
                bound(access, allowed, root);
                budget -= revisionCount(connection, root.scope());
                if (budget < 0) {
                    throw new DomainException(
                        422,
                        "DELIVERY_ATTENTION_SCAN_LIMIT",
                        "待核对读取超过完整日志重放预算，请缩小资源池范围"
                    );
                }
                var journal = PostgresDeliveryReceiptRepository.readVerifiedInTransaction(
                    connection,
                    root.scope()
                );
                if (!root.recordedAt().equals(journal.firstRecordedAt())) {
                    throw unavailable();
                }
                var entry = entry(journal);
                if (
                    status == AttentionStatus.ATTENTION
                        ? Set.of("UNKNOWN", "FAILED", "DEAD_LETTER").contains(entry.recordedStatus())
                        : status.name().equals(entry.recordedStatus())
                ) {
                    matches.add(entry);
                }
            }
            var remaining = matches
                .stream()
                .filter(
                    entry ->
                        before == null ||
                        entry.firstRecordedAt().isBefore(before.firstRecordedAt()) ||
                        (entry.firstRecordedAt().equals(before.firstRecordedAt()) &&
                            entry.deliveryId().toString().compareTo(before.deliveryId().toString()) < 0)
                )
                .limit(size + 1L)
                .toList();
            var visible = remaining.stream().limit(size).toList();
            Cursor next = null;
            if (remaining.size() > size) {
                var last = visible.get(visible.size() - 1);
                next = new Cursor(last.firstRecordedAt(), last.deliveryId());
            }
            return new AttentionPage(
                new Page(access, observed, size, matches.size(), visible, next),
                status,
                roots.size()
            );
        });
    }

 @Override public Detail detail(Access a,UUID id,Long beforeVersion,int size){if(id==null||size<1||size>100||beforeVersion!=null&&(beforeVersion<1||beforeVersion>12001))throw invalid();return read(a,(c,allowed,at)->{
  var loaded=load(c,a,id,allowed);var steps=steps(loaded);long before=beforeVersion==null?loaded.journal().state().version()+1:beforeVersion;
  var selected=steps.stream().filter(s->s.version()<before).sorted(Comparator.comparingLong(Step::version).reversed()).limit(size+1L).toList();boolean more=selected.size()>size;var page=selected.subList(0,Math.min(size,selected.size()));return new Detail(a,at,entry(loaded),page,more?page.get(page.size()-1).version():null);
 });}
 @Override public Recovery recover(Access a,UUID id,UUID command){if(id==null||command==null)throw invalid();return read(a,(c,allowed,at)->{var loaded=load(c,a,id,allowed);var original=steps(loaded).stream().filter(s->command.equals(s.commandId())).findFirst().orElseThrow(PostgresDeliveryJournalReader::missing);return new Recovery(a,at,entry(loaded),original);});}
 private ReadJournal load(Connection c,Access a,UUID id,Set<UUID> allowed)throws SQLException{
  try(var q=query(c,"SELECT tenant_id,delivery_id,resource_pool_id,event_id,channel,receiver_id,stage,recorded_at FROM public.mop_delivery_receipt_journal WHERE tenant_id=? AND delivery_id=? AND version=0 FOR SHARE")){q.setObject(1,a.tenantId());q.setObject(2,id);try(var r=q.executeQuery()){if(!r.next())throw missing();var root=root(r);bound(a,allowed,root);var loaded=PostgresDeliveryReceiptRepository.readVerifiedInTransaction(c,root.scope());if(!root.recordedAt().equals(loaded.firstRecordedAt()))throw unavailable();return loaded;}}
 }
 private record Root(Scope scope,Instant recordedAt){}
 private static Root root(ResultSet r)throws SQLException{return new Root(new Scope(r.getObject("tenant_id",UUID.class),r.getObject("delivery_id",UUID.class),new Deduplication(r.getObject("resource_pool_id",UUID.class),r.getObject("event_id",UUID.class),Channel.valueOf(r.getString("channel")),r.getObject("receiver_id",UUID.class),r.getString("stage"))),r.getObject("recorded_at",OffsetDateTime.class).toInstant());}
 private static void bound(Access a,Set<UUID> allowed,Root root){if(!a.tenantId().equals(root.scope().tenantId())||!allowed.contains(root.scope().key().resourcePoolId()))throw missing();}
 private static long revisionCount(Connection c,Scope s)throws SQLException{try(var q=query(c,"SELECT count(*) FROM public.mop_delivery_receipt_journal WHERE tenant_id=? AND delivery_id=?")){q.setObject(1,s.tenantId());q.setObject(2,s.deliveryId());try(var r=q.executeQuery()){if(!r.next())throw unavailable();long count=r.getLong(1);if(count<1||count>12001)throw unavailable();return count;}}}
 static Entry entry(ReadJournal loaded){var journal=loaded.journal();var s=journal.state();var key=s.deduplication();UUID lastRequest=s.attempts().isEmpty()?null:s.attempts().get(s.attempts().size()-1).requestId();UUID startCommand=lastRequest==null?null:journal.commands().stream().filter(c->c.transition() instanceof Started started&&started.requestId().equals(lastRequest)).map(DeliveryReceiptJournal.Command::commandId).findFirst().orElseThrow(PostgresDeliveryJournalReader::unavailable);
  return new Entry(s.tenantId(),s.deliveryId(),key.resourcePoolId(),key.eventId(),key.channel().name(),key.receiverId(),key.stage(),s.version(),s.status().name(),s.queuedAt(),s.observedAt(),loaded.firstRecordedAt(),s.attempts().size(),s.receipts().size(),lastRequest,startCommand,journal.headSha256());
 }
 static List<Step> steps(ReadJournal loaded){var out=new ArrayList<Step>();var journal=loaded.journal();var state=journal.state();out.add(new Step(0,loaded.queueCommandId(),"QUEUE",null,null,null,state.queuedAt(),state.queuedAt(),loaded.firstRecordedAt()));
  for(var c:journal.commands()){long version=journal.commandVersion(c.commandId()).orElseThrow();Instant recorded=loaded.commandRecordedAt().get(c.commandId());if(recorded==null)throw unavailable();var t=c.transition();
   if(t instanceof Started start)out.add(new Step(version,c.commandId(),"START",start.requestId(),null,null,start.observedAt(),start.observedAt(),recorded));
   else if(t instanceof DeadLettered dead)out.add(new Step(version,c.commandId(),"DEAD_LETTER",null,null,null,dead.observedAt(),dead.observedAt(),recorded));
   else if(t instanceof ReceiptRecorded received){var r=received.receipt();out.add(new Step(version,c.commandId(),"RECEIPT",r.requestId(),r.receiptId(),r.status().name(),r.occurredAt(),received.observedAt(),recorded));}
   else throw unavailable();
  }return List.copyOf(out);
 }
 private Set<UUID> configured(UUID tenant){var value=pools.apply(tenant);if(value==null||value.isEmpty())throw new DomainException(503,"DELIVERY_JOURNAL_SCOPE_PENDING","投递日志的明确资源池读取范围尚未配置");if(value.size()>100||value.stream().anyMatch(Objects::isNull))throw unavailable();return Set.copyOf(value);}
 static void runtime(Connection c)throws SQLException{try(var q=query(c,"SELECT current_user='mop_test_runtime' AND session_user='mop_test_runtime'");var r=q.executeQuery()){if(!r.next()||!r.getBoolean(1))throw unavailable();}}
 static void authority(Connection c,Access a)throws SQLException{
  for(String cap:List.of("ticket_notes:read_internal","audit:read"))PostgresAuthorization.require(c,a.actorId().toString(),a.tenantId().toString(),a.permissionVersion(),cap);
  try(var q=query(c,"SELECT m.role_code FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.active AND u.active AND m.permission_version=? FOR SHARE OF m,u")){q.setObject(1,a.tenantId());q.setObject(2,a.actorId());q.setLong(3,a.permissionVersion());try(var r=q.executeQuery()){if(!r.next())throw new DomainException(401,"SESSION_EXPIRED","投递日志读取授权已变化");if(!"padmin".equals(r.getString(1)))throw new DomainException(403,"DELIVERY_JOURNAL_FORBIDDEN","当前角色不能读取内部投递日志");}}
 }
 private static Instant now(Connection c)throws SQLException{try(var q=query(c,"SELECT date_trunc('milliseconds',clock_timestamp())");var r=q.executeQuery()){if(!r.next())throw unavailable();return r.getObject(1,OffsetDateTime.class).toInstant();}}
 private static PreparedStatement query(Connection c,String sql)throws SQLException{var q=c.prepareStatement(sql);q.setQueryTimeout(10);return q;}
 private static void time(PreparedStatement q,int i,Instant t)throws SQLException{q.setObject(i,OffsetDateTime.ofInstant(t,ZoneOffset.UTC));}
 private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","投递日志读取参数无效");}
 private static DomainException missing(){return new DomainException(404,"DELIVERY_JOURNAL_NOT_FOUND","当前范围未找到投递日志或原命令；不表示供应商未收到请求");}
 private static DomainException limit(){return new DomainException(422,"DELIVERY_JOURNAL_READ_LIMIT","本页完整日志超过核对预算，请减少每页数量；未返回部分结果");}
 private static DomainException unavailable(){return new DomainException(503,"DELIVERY_JOURNAL_UNAVAILABLE","投递日志、完整性或读取授权无法核对；未查询供应商或重新发送");}
}
