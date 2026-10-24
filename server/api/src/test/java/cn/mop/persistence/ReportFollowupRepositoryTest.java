package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.reporting.*;
import cn.mop.domain.reporting.ReportPublication.*;
import cn.mop.domain.reporting.ReportPublicationRepository.*;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** SQL protocol tests with doubles, not designated PostgreSQL evidence. */
class ReportFollowupRepositoryTest {
 final UUID tenant=UUID.fromString("79000000-0000-4000-8000-000000000001"),actor=UUID.fromString("79000000-0000-4000-8000-000000000002"),id=UUID.fromString("79000000-0000-4000-8000-000000000003");
 final Instant from=Instant.parse("2026-10-01T00:00:00Z"),now=Instant.parse("2026-10-24T00:00:00Z");
 final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
 final Access access=new Access(actor.toString(),tenant.toString(),7,"synthetic-unit");
 final ReportFollowupWorklist.Query criteria=new ReportFollowupWorklist.Query(from,now,ReportReadership.Filter.FOLLOWUP_DUE,null);
 State published(String hash){
  var doc=new Document(tenant,id,id,null,1,Kind.QUARTERLY,Scope.ISOLATED_TEST,hash,"b".repeat(64),"unit","unit",actor,from,List.of());
  var html=new Artifact(Format.HTML,hash,"c".repeat(64),"c".repeat(64),"unit","unit","f".repeat(64),200,null,from);
  var pdf=new Artifact(Format.PDF,hash,"c".repeat(64),"d".repeat(64),"unit","unit","f".repeat(64),300,1,from);
  return new State(doc,5,Phase.PUBLISHED,html,pdf,actor,from,actor,from,"unit",List.of(actor),null,from);
 }
 @Test void snapshotStartsBeforeQueriesCommitsAndCannotNest()throws Exception{
  var source=mock(DataSource.class);var c=mock(Connection.class);when(source.getConnection()).thenReturn(c);
  var platform=new PostgresPlatformRepository(source,mapper);
  assertEquals("ok",platform.identitySnapshot(conn->{assertSame(c,conn);return "ok";}));
  var order=inOrder(c);order.verify(c).setAutoCommit(false);order.verify(c).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);order.verify(c).commit();order.verify(c).close();
  assertThrows(IllegalStateException.class,()->platform.identityTransaction(conn->platform.identitySnapshot(other->"bad")));
  verify(c).rollback();
 }
 @Test void completeCurrentWindowReadUsesOneIdentitySnapshotAndNeverWrites()throws Exception{
  var fixture=new Fixture(1,2);try(fixture){var page=fixture.repo.followups(access,criteria);assertEquals(1,page.scannedDocuments());assertEquals(1,page.followupDueReports());assertEquals(id,page.items().get(0).documentId());assertNull(page.next());
   verify(fixture.platform).identitySnapshot(any());verify(fixture.platform,never()).identityTransaction(any());verify(fixture.platform,never()).audit(any(),any(),any(),any(),any(),any(),any(),any());
   assertTrue(fixture.sql.stream().allMatch(s->s.startsWith("SELECT")));assertTrue(fixture.sql.stream().anyMatch(s->s.contains("captured_at>=?")&&s.contains("LIMIT 1001")));}
 }
 @Test void tooManyDocumentsOrPayloadBytesRejectBeforeLoadingPayload()throws Exception{
  for(var values:List.of(new long[]{1001,2},new long[]{1,33554433}))try(var f=new Fixture((int)values[0],values[1])){
   assertEquals(422,assertThrows(DomainException.class,()->f.repo.followups(access,criteria)).status);assertFalse(f.sql.stream().anyMatch(s->s.contains("SELECT label,payload")));}
 }
 @Test void wrongSourceDigestFailsAndNoRendererOrAuditRuns()throws Exception{
  try(var f=new Fixture(1,2)){f.invalidDigest=true;assertEquals(503,assertThrows(DomainException.class,()->f.repo.followups(access,criteria)).status);}
 }
 final class Fixture implements AutoCloseable {
  final Connection c=mock(Connection.class);final PostgresPlatformRepository platform=mock(PostgresPlatformRepository.class);
  final org.mockito.MockedStatic<ReportPublicationSchemaVerifier> schema=mockStatic(ReportPublicationSchemaVerifier.class);
  final org.mockito.MockedStatic<PostgresAuthorization> auth=mockStatic(PostgresAuthorization.class);
  final List<String> sql=new ArrayList<>();final PostgresReportPublicationRepository repo;boolean invalidDigest;
  Fixture(int count,long bytes)throws Exception{
   byte[] payload="{}".getBytes();String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload));String state=mapper.writeValueAsString(published(hash));
   when(platform.membership(actor.toString(),tenant.toString())).thenReturn(Optional.of(new Membership(actor.toString(),tenant.toString(),"padmin",7,true)));
   doAnswer(call->((Function<Connection,?>)call.getArgument(0)).apply(c)).when(platform).identitySnapshot(any());
   when(c.prepareStatement(anyString())).thenAnswer(call->{String text=call.getArgument(0);sql.add(text);var s=mock(PreparedStatement.class);var r=mock(ResultSet.class);when(s.executeQuery()).thenReturn(r);when(r.next()).thenReturn(true,false);
    if(text.startsWith("SELECT id,octet_length")){var i=new java.util.concurrent.atomic.AtomicInteger();when(r.next()).thenAnswer(v->i.getAndIncrement()<count);when(r.getString(1)).thenReturn(id.toString());when(r.getLong(2)).thenReturn(bytes);}
    else if(text.contains("SELECT label,payload")){when(r.getString(1)).thenReturn("Synthetic");when(r.getBytes(2)).thenReturn(payload);when(r.getString(3)).thenAnswer(v->invalidDigest?"0".repeat(64):hash);}
    else if(text.contains("SELECT state::text"))when(r.getString(1)).thenReturn(state);
    else if(text.contains("SELECT customer_id,document_sha256"))when(r.next()).thenReturn(false);
    else if(text.contains("clock_timestamp")||text.contains("CURRENT_TIMESTAMP"))when(r.getObject(1,OffsetDateTime.class)).thenReturn(now.atOffset(ZoneOffset.UTC));
    return s;});
   repo=new PostgresReportPublicationRepository(platform,mapper,(d,b,n)->{throw new AssertionError("No rendering");},t->{throw new AssertionError("No policy publication");},null);
  }
  public void close(){auth.close();schema.close();}
 }
}
