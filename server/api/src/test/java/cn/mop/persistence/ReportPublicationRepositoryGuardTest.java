package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.reporting.*;
import cn.mop.domain.reporting.ReportPublication.*;
import cn.mop.domain.reporting.ReportPublicationRepository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ReportPublicationRepositoryGuardTest {
 final String actor="79000000-0000-4000-8000-000000000001",tenant="79000000-0000-4000-8000-000000000002",id="79000000-0000-4000-8000-000000000003";
 final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
 @Test void schemaDriftFailsClosedBeforeReadingRows()throws Exception{
  var platform=mock(PostgresPlatformRepository.class);var c=mock(Connection.class);doAnswer(call->((Function<Connection,?>)call.getArgument(0)).apply(c)).when(platform).identityTransaction(any());
  try(var guard=mockStatic(ReportPublicationSchemaVerifier.class)){
   guard.when(()->ReportPublicationSchemaVerifier.verify(c)).thenThrow(new SQLException("synthetic drift"));
   var repo=new PostgresReportPublicationRepository(platform,mapper,(d,b,n)->{throw new AssertionError();},t->null,null);
   assertEquals(503,assertThrows(DomainException.class,()->repo.get(new Access(actor,tenant,1,"unit"),id)).status);verifyNoInteractions(c);
  }
 }
 @Test void migrationResourcePinnedAndNeverAppliedByVerifier()throws Exception{ReportPublicationSchemaVerifier.resource();assertEquals(5,ReportPublicationSchemaVerifier.TABLES.size());}
 @Test void customerCannotReadAnUnpublishedDocumentEvenWhenTenantMatches()throws Exception{
  var platform=mock(PostgresPlatformRepository.class);var c=mock(Connection.class);doAnswer(call->((Function<Connection,?>)call.getArgument(0)).apply(c)).when(platform).identityTransaction(any());
  when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"viewer",1,true)));
  byte[] payload="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);String hash=ReportPublicationSchemaVerifier.hash(payload);
  var document=new Document(UUID.fromString(tenant),UUID.fromString(id),UUID.fromString(id),null,1,Kind.QUARTERLY,Scope.CUSTOMER_DELIVERY,hash,"a".repeat(64),"v1","v1",UUID.fromString(actor),Instant.parse("2026-10-23T00:00:00Z"),List.of());
  when(c.prepareStatement(anyString())).thenAnswer(call->{var s=mock(PreparedStatement.class);var r=mock(ResultSet.class);when(s.executeQuery()).thenReturn(r);when(r.next()).thenReturn(true,false);String sql=call.getArgument(0);
   if(sql.contains("SELECT label,payload")){when(r.getString(1)).thenReturn("Synthetic");when(r.getBytes(2)).thenReturn(payload);when(r.getString(3)).thenReturn(hash);}
   else if(sql.contains("SELECT state::text"))when(r.getString(1)).thenReturn(mapper.writeValueAsString(ReportPublication.create(document)));
   return s;});
  try(var guard=mockStatic(ReportPublicationSchemaVerifier.class);var auth=mockStatic(PostgresAuthorization.class)){
   var repo=new PostgresReportPublicationRepository(platform,mapper,(d,b,n)->{throw new AssertionError();},t->null,null);
   assertEquals(404,assertThrows(DomainException.class,()->repo.get(new Access(actor,tenant,1,"unit"),id)).status);
  }
 }
}
