package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.EventNoiseTrendReader.*;
import cn.mop.domain.monitoring.MonitoringEventPipeline.NoiseLayer;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** JDBC protocol doubles only; no PostgreSQL execution or current monitoring evidence. */
class EventNoiseTrendJdbcTest {
 static UUID id(int n){return UUID.fromString("79000000-0000-4000-8000-"+String.format("%012d",n));}
 final Access access=new Access(id(1),id(2),1);final Instant at=Instant.parse("2026-10-23T20:00:00Z"),from=at.minusSeconds(86400),rawAt=from.plusSeconds(1);
 final DataSource source=mock(DataSource.class);final Connection c=mock(Connection.class);final PostgresPlatformRepository platform=mock(PostgresPlatformRepository.class);final Map<String,PreparedStatement> sql=new LinkedHashMap<>();
 String role="padmin";boolean missingMarkers=false,incomplete=false,ignoreCursor=false;
 EventNoiseTrendJdbcTest()throws Exception{when(source.getConnection()).thenReturn(c);when(c.prepareStatement(anyString())).thenAnswer(call->{String text=call.getArgument(0);var q=mock(PreparedStatement.class);sql.put(text,q);List<Map<Object,Object>> data;
  if(text.contains("m.role_code"))data=List.of(Map.of(1,role));
  else if(text.contains("clock_timestamp"))data=List.of(Map.of(1,at.atOffset(ZoneOffset.UTC)));
  else if(text.startsWith("SELECT floor"))data=List.of(Map.of(1,0,2,"RAW_INTAKE",3,3L),Map.of(1,0,2,"MERGE",3,1L));
  else if(text.startsWith("SELECT layer"))data=List.of(Map.of(1,"RAW_INTAKE",2,3L),Map.of(1,"MERGE",2,1L));
  else if(text.startsWith("SELECT count"))data=List.of(Map.of(1,3L));
  else {data=new ArrayList<>();int start=text.contains("AND (recorded_at,id)")&&!ignoreCursor?1:0;for(int i=start;i<(incomplete?2:3);i++){var row=new HashMap<Object,Object>();row.put("id",id(100-i));row.put("event_id",id(200));row.put("recorded_at",rawAt.atOffset(ZoneOffset.UTC));row.put("rule_name","Synthetic proxy");row.put("severity","HIGH");row.put("detail","Synthetic raw "+i);data.add(row);}if(text.contains("JOIN public.mop_monitoring_event")&&missingMarkers)data=List.of();}
  when(q.executeQuery()).thenReturn(rows(data));return q;});}
 ResultSet rows(List<Map<Object,Object>> values){var index=new AtomicInteger(-1);return mock(ResultSet.class,call->{String name=call.getMethod().getName();if(name.equals("next"))return index.incrementAndGet()<values.size();if(name.startsWith("get")&&call.getArguments().length>0){Object value=values.get(index.get()).get(call.getArgument(0));if(value==null)throw new AssertionError("Unknown synthetic column "+call.getArgument(0));return switch(name){case "getInt"->((Number)value).intValue();case "getLong"->((Number)value).longValue();default->value;};}return null;});}
 PostgresEventNoiseTrendReader reader(){return new PostgresEventNoiseTrendReader(source,platform);}
 @Test void completeSeriesLayersAndMarkersShareReadOnlySnapshot()throws Exception{try(var auth=mockStatic(PostgresAuthorization.class)){var result=reader().read(access,Window.HOURS_24);assertEquals(3,result.totals().get(NoiseLayer.RAW_INTAKE));assertEquals(2,result.buckets().get(0).afterFingerprintMergeCount());assertEquals(3,result.markers().size());verify(c).setReadOnly(true);verify(c).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);verify(c).setAutoCommit(false);verify(c).commit();var aggregate=sql.keySet().stream().filter(s->s.startsWith("SELECT floor")).findFirst().orElseThrow();assertTrue(aggregate.contains("recorded_at>=? AND recorded_at<?"));var q=sql.get(aggregate);verify(q).setObject(4,from.atOffset(ZoneOffset.UTC));verify(q).setObject(5,at.atOffset(ZoneOffset.UTC));auth.verify(()->PostgresAuthorization.require(c,access.actorId().toString(),access.tenantId().toString(),1,"dashboard:read"));auth.verify(()->PostgresAuthorization.require(c,access.actorId().toString(),access.tenantId().toString(),1,"tickets:read"));assertTrue(sql.keySet().stream().allMatch(s->s.startsWith("SELECT")));}}
 @Test void markerCoverageMismatchCannotBecomeAnEmptyAnnotationSet()throws Exception{missingMarkers=true;try(var ignored=mockStatic(PostgresAuthorization.class)){assertEquals(503,assertThrows(DomainException.class,()->reader().read(access,Window.HOURS_24)).status);verify(c).rollback();verify(c,never()).commit();}}
 @Test void customerRoleIsRejectedBeforeAnyNoiseQuery(){role="tadmin";try(var ignored=mockStatic(PostgresAuthorization.class)){assertEquals(403,assertThrows(DomainException.class,()->reader().read(access,Window.HOURS_24)).status);assertTrue(sql.keySet().stream().noneMatch(s->s.contains("mop_event_noise_record")));}}
 @Test void rawRecordsCountFullBucketWhileCursorOnlyNarrowsPage()throws Exception{try(var ignored=mockStatic(PostgresAuthorization.class)){var cursor=new RecordCursor(rawAt,id(100));var page=reader().records(access,from,from.plusSeconds(3600),cursor,20);assertEquals(3,page.totalCount());assertEquals(2,page.items().size());var query=sql.keySet().stream().filter(s->s.contains("AND (recorded_at,id)")).findFirst().orElseThrow();verify(sql.get(query)).setObject(4,rawAt.atOffset(ZoneOffset.UTC));verify(sql.get(query)).setString(5,id(100).toString());assertTrue(sql.keySet().stream().filter(s->s.startsWith("SELECT count")).noneMatch(s->s.contains("(recorded_at,id)")));}}
 @Test void incompleteFirstPageAndCursorRepeatAreRejected(){try(var ignored=mockStatic(PostgresAuthorization.class)){incomplete=true;assertEquals(503,assertThrows(DomainException.class,()->reader().records(access,from,from.plusSeconds(3600),null,20)).status);incomplete=false;ignoreCursor=true;assertEquals(503,assertThrows(DomainException.class,()->reader().records(access,from,from.plusSeconds(3600),new RecordCursor(rawAt,id(100)),20)).status);}}
 @Test void originalFunnelUsesTheSameHalfOpenObservationBoundary()throws Exception{try(var ignored=mockStatic(PostgresAuthorization.class)){var repo=new PostgresMonitoringEventCenterRepository(source,platform);var result=repo.noiseFunnel(new cn.mop.domain.monitoring.MonitoringEventCenterRepository.Access(access.tenantId().toString(),access.actorId().toString(),1,"synthetic"),"24h");assertEquals(3,result.rawIntakeCount());var query=sql.keySet().stream().filter(s->s.startsWith("SELECT layer")).findFirst().orElseThrow();assertTrue(query.contains("recorded_at>=? AND recorded_at<?"));verify(sql.get(query)).setObject(2,from.atOffset(ZoneOffset.UTC));verify(sql.get(query)).setObject(3,at.atOffset(ZoneOffset.UTC));}}
}
