package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.persistence.PostgresBackupVersionRepository.*;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class BackupVersionInventoryBoundaryTest {
 final UUID tenant=UUID.randomUUID(),asset=UUID.randomUUID();final Access access=new Access(tenant,UUID.randomUUID(),1);final InventoryScope scope=new InventoryScope(tenant,asset);final Instant at=Instant.parse("2026-10-21T00:00:00.123Z");final AtomicInteger connections=new AtomicInteger();
 final InventoryAuthorizer allow=(c,a,s)->{};
 DataSource failing(){return(DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataSource.class},(p,m,a)->{connections.incrementAndGet();throw new SQLException("SYNTHETIC_PRIVATE_DETAIL","08006");});}
 PostgresBackupVersionRepository repo(DataSource source){return new PostgresBackupVersionRepository(source,(c,a,s,o)->{},(a,c,r)->{throw new AssertionError("No object access in inventory");},(c,a,s,r,l,e)->{throw new AssertionError("No completion write proof in inventory");});}
 @Test void explicitInventoryAuthorityAndInputLimitsRequiredBeforeDatabase(){var r=repo(failing());assertThrows(DomainException.class,()->r.inventory(access,scope,null,15,null));for(int limit:new int[]{0,-1,51,Integer.MAX_VALUE})assertThrows(DomainException.class,()->r.inventory(access,scope,null,limit,allow));assertThrows(DomainException.class,()->r.inventory(null,scope,null,15,allow));assertThrows(DomainException.class,()->r.inventory(access,null,null,15,allow));assertEquals(0,connections.get());}
 @Test void cursorBindsTenantAndAssetBeforeConnection(){var r=repo(failing());for(var s:List.of(new InventoryScope(UUID.randomUUID(),asset),new InventoryScope(tenant,UUID.randomUUID()))){assertEquals(404,assertThrows(DomainException.class,()->r.inventory(access,scope,new InventoryCursor(s,at,UUID.randomUUID()),15,allow)).status);}assertEquals(404,assertThrows(DomainException.class,()->r.inventory(new Access(UUID.randomUUID(),access.actorId(),1),scope,null,15,allow)).status);assertEquals(0,connections.get());}
 @Test void cursorRequiresCanonicalMillisecondStorageRange(){assertThrows(DomainException.class,()->new InventoryScope(null,asset));assertThrows(DomainException.class,()->new InventoryScope(tenant,null));for(Instant t:new Instant[]{null,at.plusNanos(1),Instant.parse("0000-12-31T23:59:59Z"),Instant.parse("+10000-01-01T00:00:00Z")})assertThrows(DomainException.class,()->new InventoryCursor(scope,t,UUID.randomUUID()));assertThrows(DomainException.class,()->new InventoryCursor(null,at,UUID.randomUUID()));assertThrows(DomainException.class,()->new InventoryCursor(scope,at,null));assertEquals(at,new InventoryCursor(scope,at,UUID.randomUUID()).registeredAt());}
 @Test void sqlFailureReportsReadUnavailableWithoutDriverDetails(){var e=assertThrows(DomainException.class,()->repo(failing()).inventory(access,scope,null,15,allow));assertEquals(503,e.status);assertEquals("BACKUP_VERSION_INVENTORY_UNAVAILABLE",e.code);assertFalse(e.getMessage().contains("SYNTHETIC_PRIVATE_DETAIL"));assertNull(e.getCause());}
 @Test void summaryNeverGrantsDownloadOrExposesStorageKeyProofOrActor(){var item=new InventoryItem(UUID.randomUUID(),asset,UUID.randomUUID(),1,2,at,at,"a".repeat(64),300);assertTrue(item.registrationAndRunCompletionRecorded());assertFalse(item.currentObjectHealthVerified());assertFalse(item.downloadAuthorized());assertFalse(item.deviceExecutionAuthorized());assertFalse(item.retentionVerified());assertFalse(item.recoveryVerified());assertEquals(Set.of("versionId","assetId","runId","attemptNumber","completedRunVersion","objectObservedAt","registeredAt","encryptedSha256","encryptedBytes"),new HashSet<>(Arrays.stream(InventoryItem.class.getRecordComponents()).map(c->c.getName()).toList()));var items=new ArrayList<InventoryItem>();items.add(item);var page=new InventoryPage(items,false,null);items.clear();assertEquals(1,page.items().size());assertThrows(UnsupportedOperationException.class,()->page.items().clear());}
 static class EmptyDatabase {
  final List<String> sql=new ArrayList<>();final AtomicInteger commits=new AtomicInteger(),rollbacks=new AtomicInteger(),authorizations=new AtomicInteger();
  @SuppressWarnings("unchecked") <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){return(T)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{type},handler);}
  DataSource source(){return proxy(DataSource.class,(p,m,a)->{if(!m.getName().equals("getConnection"))throw new AssertionError(m);return proxy(Connection.class,(cp,cm,ca)->switch(cm.getName()){
   case "setAutoCommit","setTransactionIsolation","close"->null;case "getAutoCommit"->false;
   case "commit"->{commits.incrementAndGet();yield null;}case "rollback"->{rollbacks.incrementAndGet();yield null;}
   case "createStatement"->proxy(Statement.class,(s,sm,sa)->{if(sm.getName().equals("execute")){sql.add((String)sa[0]);return true;}return null;});
   case "prepareStatement"->{String query=(String)ca[0];sql.add(query);yield proxy(PreparedStatement.class,(q,qm,qa)->{if(qm.getName().equals("executeQuery")){AtomicInteger n=new AtomicInteger();return proxy(ResultSet.class,(r,rm,ra)->rm.getName().equals("next")?query.contains("FROM public.mop_asset ")&&n.getAndIncrement()==0:null);}return null;});}
   default->throw new AssertionError(cm);
  });});}
 }
 @Test void evenEmptyInventoryAuthorizesBeforeAndAfterAndUsesBoundedOrderedQuery(){var db=new EmptyDatabase();var page=repo(db.source()).inventory(access,scope,null,15,(c,a,s)->{assertFalse(c.getAutoCommit());assertEquals(scope,s);db.authorizations.incrementAndGet();});assertTrue(page.items().isEmpty());assertFalse(page.hasMore());assertNull(page.nextBefore());assertEquals(2,db.authorizations.get());assertEquals(1,db.commits.get());assertEquals(0,db.rollbacks.get());assertTrue(db.sql.stream().anyMatch(s->s.contains("ORDER BY registered_at DESC,version_id DESC LIMIT ?")));assertFalse(db.sql.stream().anyMatch(s->s.startsWith("INSERT")||s.startsWith("UPDATE")||s.startsWith("DELETE")));}
 @Test void finalEnumerationVetoRollsBackAndReturnsNoPartialEmptyResult(){var db=new EmptyDatabase();var e=assertThrows(DomainException.class,()->repo(db.source()).inventory(access,scope,null,15,(c,a,s)->{if(db.authorizations.incrementAndGet()==2)throw new DomainException(403,"REVOKED","SYNTHETIC_PRIVATE_DETAIL");}));assertEquals(403,e.status);assertEquals(0,db.commits.get());assertEquals(1,db.rollbacks.get());assertFalse(e.getMessage().contains("SYNTHETIC_PRIVATE_DETAIL"));}
 @Test void unexpectedAuthorizerFailureIsSanitized(){var db=new EmptyDatabase();var e=assertThrows(DomainException.class,()->repo(db.source()).inventory(access,scope,null,15,(c,a,s)->{throw new IllegalStateException("SYNTHETIC_PRIVATE_DETAIL");}));assertEquals(503,e.status);assertNull(e.getCause());assertFalse(e.getMessage().contains("SYNTHETIC_PRIVATE_DETAIL"));assertEquals(1,db.rollbacks.get());}
}
