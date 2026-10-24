package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.notification.*;
import cn.mop.domain.notification.DeliveryReceiptState.*;
import cn.mop.persistence.DeliveryReceiptSnapshotCodec.Scope;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** JDBC protocol doubles, no actual PostgreSQL or provider verification. */
class DeliveryJournalReaderJdbcTest {
 final UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),pool=UUID.randomUUID(),delivery=UUID.randomUUID(),event=UUID.randomUUID(),receiver=UUID.randomUUID(),command=UUID.randomUUID();final Instant at=Instant.parse("2026-10-24T00:00:00Z");
 final DataSource source=mock(DataSource.class);final Connection c=mock(Connection.class);final DeliveryJournalReader.Access access=new DeliveryJournalReader.Access(tenant,actor,7);final List<String> queries=new ArrayList<>();Set<UUID> allowed;boolean present=true,runtime=true;long total=1,revisions=1;MockedStatic<PostgresAuthorization> auth;MockedStatic<DeliveryReceiptSchemaVerifier> schema;MockedStatic<PostgresDeliveryReceiptRepository> journals;PostgresDeliveryJournalReader reader;
 final Scope scope=new Scope(tenant,delivery,new Deduplication(pool,event,Channel.EMAIL,receiver,"INITIAL"));
 @BeforeEach void setup()throws Exception{allowed=Set.of(pool);when(source.getConnection()).thenReturn(c);when(c.createArrayOf(eq("uuid"),any())).thenReturn(mock(java.sql.Array.class));auth=mockStatic(PostgresAuthorization.class);schema=mockStatic(DeliveryReceiptSchemaVerifier.class);journals=mockStatic(PostgresDeliveryReceiptRepository.class);journals.when(()->PostgresDeliveryReceiptRepository.readVerifiedInTransaction(c,scope)).thenReturn(new PostgresDeliveryReceiptRepository.ReadJournal(DeliveryReceiptJournal.queued(scope,at),command,at,Map.of(command,at)));
  when(c.prepareStatement(anyString())).thenAnswer(call->{String sql=call.getArgument(0);queries.add(sql);var q=mock(PreparedStatement.class);when(q.executeQuery()).thenAnswer(inv->{var r=mock(ResultSet.class);boolean exists=true;if(sql.startsWith("SELECT current_user"))when(r.getBoolean(1)).thenReturn(runtime);else if(sql.startsWith("SELECT m.role_code"))when(r.getString(1)).thenReturn("padmin");else if(sql.startsWith("SELECT date_trunc"))when(r.getObject(1,OffsetDateTime.class)).thenReturn(at.plusSeconds(5).atOffset(ZoneOffset.UTC));else if(sql.startsWith("SELECT count(*)"))when(r.getLong(1)).thenReturn(sql.contains("version=0")?total:revisions);else if(sql.startsWith("SELECT tenant_id")){exists=present;when(r.getObject("tenant_id",UUID.class)).thenReturn(tenant);when(r.getObject("delivery_id",UUID.class)).thenReturn(delivery);when(r.getObject("resource_pool_id",UUID.class)).thenReturn(pool);when(r.getObject("event_id",UUID.class)).thenReturn(event);when(r.getObject("receiver_id",UUID.class)).thenReturn(receiver);when(r.getString("channel")).thenReturn("EMAIL");when(r.getString("stage")).thenReturn("INITIAL");when(r.getObject("recorded_at",OffsetDateTime.class)).thenReturn(at.atOffset(ZoneOffset.UTC));}else throw new AssertionError("Unexpected SQL: "+sql);when(r.next()).thenReturn(exists,false);return r;});return q;});reader=new PostgresDeliveryJournalReader(source,t->allowed);
 }
 @AfterEach void cleanup(){journals.close();schema.close();auth.close();}
 @Test void listUsesRepeatableReadFullChainAndNoWriteSql()throws Exception{var page=reader.list(access,null,null,10);assertEquals(1,page.totalCount());assertEquals("QUEUED",page.items().get(0).recordedStatus());assertNull(page.next());assertTrue(queries.stream().allMatch(q->q.startsWith("SELECT")));verify(c).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);verify(c).commit();verify(c,never()).setReadOnly(true);journals.verify(()->PostgresDeliveryReceiptRepository.readVerifiedInTransaction(c,scope));}
 @Test void absentScopeIsNotAnEmptySuccess(){allowed=Set.of();assertEquals(503,assertThrows(DomainException.class,()->reader.list(access,null,null,10)).status);journals.verifyNoInteractions();}
 @Test void foreignPoolIs404BeforeLedgerRead(){assertEquals(404,assertThrows(DomainException.class,()->reader.list(access,UUID.randomUUID(),null,10)).status);journals.verifyNoInteractions();assertTrue(queries.stream().noneMatch(q->q.contains("mop_delivery_receipt_journal")));}
 @Test void missingObjectAndMissingCommandAre404(){present=false;assertEquals(404,assertThrows(DomainException.class,()->reader.detail(access,delivery,null,100)).status);present=true;assertEquals(404,assertThrows(DomainException.class,()->reader.recover(access,delivery,UUID.randomUUID())).status);}
 @Test void queueRootRecoveryIsReadOnlyAndDoesNotInventRequest(){var recovered=reader.recover(access,delivery,command);assertEquals("QUEUE",recovered.original().kind());assertNull(recovered.original().requestId());assertTrue(queries.stream().allMatch(q->q.startsWith("SELECT")));}
 @Test void changedReadPoolScopeBeforeReturnRejectsData(){journals.when(()->PostgresDeliveryReceiptRepository.readVerifiedInTransaction(c,scope)).thenAnswer(call->{allowed=Set.of(UUID.randomUUID());return new PostgresDeliveryReceiptRepository.ReadJournal(DeliveryReceiptJournal.queued(scope,at),command,at,Map.of(command,at));});assertEquals(401,assertThrows(DomainException.class,()->reader.list(access,null,null,10)).status);}
 @Test void malformedCompletePopulationDoesNotBecomePartialPage(){total=2;assertEquals(503,assertThrows(DomainException.class,()->reader.list(access,null,null,10)).status);journals.verifyNoInteractions();}
 @Test void schemaDriftPreventsPrivateLedgerReads()throws Exception{schema.when(()->DeliveryReceiptSchemaVerifier.verify(c)).thenThrow(new SQLException("unit drift"));assertEquals(503,assertThrows(DomainException.class,()->reader.list(access,null,null,10)).status);journals.verifyNoInteractions();}
 @Test void privilegedOrImpersonatedDatabaseUserCannotReadPrivateLedger(){runtime=false;assertEquals(503,assertThrows(DomainException.class,()->reader.list(access,null,null,10)).status);journals.verifyNoInteractions();assertEquals(1,queries.size());}
 @Test void rowOutsideRequestedCursorDoesNotEscapeIntoThePage(){var before=new DeliveryJournalReader.Cursor(at.minusSeconds(1),delivery);assertEquals(503,assertThrows(DomainException.class,()->reader.list(access,null,before,10)).status);journals.verifyNoInteractions();}
}
