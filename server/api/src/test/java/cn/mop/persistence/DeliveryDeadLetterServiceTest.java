package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.notification.DeliveryReceiptState.*;
import cn.mop.persistence.PostgresDeliveryReceiptRepository.Access;
import cn.mop.persistence.DeliveryReceiptSnapshotCodec.Scope;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Scoped service/transaction protocol doubles; designated PG business verification is separate. */
class DeliveryDeadLetterServiceTest {
 final UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),delivery=UUID.randomUUID(),pool=UUID.randomUUID(),event=UUID.randomUUID(),receiver=UUID.randomUUID(),command=UUID.randomUUID();
 final Access access=new Access(tenant,actor,7);final Instant at=Instant.parse("2026-10-24T12:00:00Z");
 @Test void disabledAndInvalidCommandsCannotOpenTransactions(){var platform=mock(PostgresPlatformRepository.class);var source=mock(DataSource.class);
  var service=new DeliveryDeadLetterService(source,platform,false,t->Set.of(pool),t->Set.of(pool));assertEquals(503,assertThrows(DomainException.class,()->service.dispose(access,delivery,2,command,"reason","unit")).status);
  assertEquals(400,assertThrows(DomainException.class,()->service.dispose(access,delivery,2,command," ","unit")).status);verifyNoInteractions(platform,source);
 }
 @Test void newDispositionUsesOwnedConnectionAndAuditBeforeReceipt()throws Exception{try(var f=new Fixture()){
  var out=(Map<?,?>)f.service.dispose(access,delivery,2,command,"Synthetic manual reason","unit");assertEquals(3L,out.get("applied_version"));assertEquals(false,out.get("fresh_send_authorized"));assertEquals("ORIGINAL_COMMAND_NOT_CURRENT_HEAD",out.get("receipt_scope"));
  var order=inOrder(f.writer,f.platform);order.verify(f.writer).deadLetterInTransaction(eq(f.c),eq(access),any(),any());order.verify(f.platform).auditInTransaction(eq(f.c),eq(actor.toString()),eq(tenant.toString()),eq("notification.delivery.dead_letter"),eq("notification_delivery"),eq(delivery.toString()),eq("Synthetic manual reason"),eq("unit"));
  verify(f.c,never()).commit();verify(f.c,never()).rollback();verify(f.source,never()).getConnection();assertFalse(f.sql.stream().anyMatch(s->s.startsWith("INSERT")||s.startsWith("UPDATE")));
 }}
 @Test void replayReauthorizesScopeWithoutNewAppendOrAudit()throws Exception{try(var f=new Fixture()){var original=Map.of("original","saved");doAnswer(call->{((Runnable)call.getArgument(6)).run();return original;}).when(f.platform).idempotentAuthorized(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),any(),any());
  assertSame(original,f.service.dispose(access,delivery,2,command,"reason","unit"));verifyNoInteractions(f.writer);verify(f.platform,never()).auditInTransaction(any(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
 }}
 @Test void readPoolNeverGrantsWriteAndForeignObjectIsHidden()throws Exception{try(var f=new Fixture()){f.writeAllowed=false;assertEquals(404,assertThrows(DomainException.class,()->f.service.dispose(access,delivery,2,command,"reason","unit")).status);verifyNoInteractions(f.writer);}}
 @Test void missingPrivateWriteScopeIsNotEmptySuccess()throws Exception{try(var f=new Fixture()){f.emptyWrite=true;assertEquals(503,assertThrows(DomainException.class,()->f.service.dispose(access,delivery,2,command,"reason","unit")).status);verifyNoInteractions(f.writer);}}
 @Test void changedPayloadIsBoundToPlatformIdempotencyHash()throws Exception{try(var f=new Fixture()){f.service.dispose(access,delivery,2,command,"reason A","unit");f.service.dispose(access,delivery,2,command,"reason B","unit");var hashes=org.mockito.ArgumentCaptor.forClass(String.class);verify(f.platform,times(2)).idempotentAuthorized(eq(actor.toString()),eq(tenant.toString()),eq("POST /api/v1/notification-deliveries/"+delivery+"/dead-letter"),eq(command.toString()),hashes.capture(),eq(7L),any(),any());assertNotEquals(hashes.getAllValues().get(0),hashes.getAllValues().get(1));}}
 @Test void auditFailureEscapesToOwningTransactionInsteadOfReturningSuccess()throws Exception{try(var f=new Fixture()){doThrow(new SQLException("Synthetic audit unavailable")).when(f.platform).auditInTransaction(any(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());assertEquals(503,assertThrows(DomainException.class,()->f.service.dispose(access,delivery,2,command,"reason","unit")).status);verify(f.c,never()).commit();}}
 final class Fixture implements AutoCloseable {
  final DataSource source=mock(DataSource.class);final Connection c=mock(Connection.class);final PostgresPlatformRepository platform=mock(PostgresPlatformRepository.class);
  final org.mockito.MockedStatic<DeliveryReceiptSchemaVerifier> schema=mockStatic(DeliveryReceiptSchemaVerifier.class);final org.mockito.MockedStatic<PostgresAuthorization> auth=mockStatic(PostgresAuthorization.class);
  final org.mockito.MockedConstruction<PostgresDeliveryReceiptRepository> constructed;final PostgresDeliveryReceiptRepository writer;final DeliveryDeadLetterService service;final List<String> sql=new ArrayList<>();boolean writeAllowed=true,emptyWrite=false;
  Fixture()throws Exception{
   when(platform.existingCommandConnection()).thenReturn(c);
   doAnswer(call->{((Runnable)call.getArgument(6)).run();return ((Supplier<?>)call.getArgument(7)).get();}).when(platform).idempotentAuthorized(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),any(),any());
   when(c.prepareStatement(anyString())).thenAnswer(call->{String q=call.getArgument(0);sql.add(q);var s=mock(PreparedStatement.class);var r=mock(ResultSet.class);when(s.executeQuery()).thenReturn(r);when(r.next()).thenReturn(true,false);
    if(q.contains("current_user"))when(r.getBoolean(1)).thenReturn(true);
    else if(q.contains("role_code"))when(r.getString(1)).thenReturn("padmin");
    else if(q.contains("SELECT resource_pool_id")){when(r.getObject(1,UUID.class)).thenReturn(pool);when(r.getObject(2,UUID.class)).thenReturn(event);when(r.getString(3)).thenReturn("EMAIL");when(r.getObject(4,UUID.class)).thenReturn(receiver);when(r.getString(5)).thenReturn("INITIAL");}
    else if(q.contains("clock_timestamp"))when(r.getObject(1,OffsetDateTime.class)).thenReturn(at.atOffset(ZoneOffset.UTC));return s;});
   constructed=mockConstruction(PostgresDeliveryReceiptRepository.class,(mock,context)->{
    when(mock.deadLetterInTransaction(eq(c),eq(access),any(),any())).thenAnswer(call->{Scope scope=call.getArgument(2);DeliveryReceiptJournal.Command cmd=call.getArgument(3);UUID request=UUID.randomUUID();var journal=DeliveryReceiptJournal.queued(scope,at.minusSeconds(3))
      .append(new DeliveryReceiptJournal.Command(0,UUID.randomUUID(),actor,7,"unit",new Started(request,at.minusSeconds(2)))).journal()
      .append(new DeliveryReceiptJournal.Command(1,UUID.randomUUID(),actor,7,"unit",new ReceiptRecorded(new Receipt(tenant,delivery,request,UUID.randomUUID(),ReceiptStatus.FAILED,at.minusSeconds(1)),at.minusSeconds(1)))).journal().append(cmd).journal();
     return new PostgresDeliveryReceiptRepository.Receipt(journal,3,false);});});
   service=new DeliveryDeadLetterService(source,platform,true,t->Set.of(pool),t->emptyWrite?Set.of():Set.of(writeAllowed?pool:UUID.randomUUID()));writer=constructed.constructed().get(0);
  }
  public void close(){constructed.close();auth.close();schema.close();}
 }
}
