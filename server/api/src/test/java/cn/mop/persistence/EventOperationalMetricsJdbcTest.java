package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.operations.EventOperationalMetricsReader.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** JDBC protocol doubles, not execution of SQL on PostgreSQL. */
class EventOperationalMetricsJdbcTest {
 static final String ACTOR="79000000-0000-4000-8000-000000000001",TENANT="79000000-0000-4000-8000-000000000002";
 final Connection connection=mock(Connection.class);final DataSource source=mock(DataSource.class);final PostgresPlatformRepository platform=mock(PostgresPlatformRepository.class);
 final List<String> sql=new ArrayList<>();final Map<String,PreparedStatement> statements=new LinkedHashMap<>();
 long population=0;String role="padmin";boolean failPage=false;
 EventOperationalMetricsJdbcTest() throws Exception {when(source.getConnection()).thenReturn(connection);when(connection.prepareStatement(anyString())).thenAnswer(call->{String s=call.getArgument(0);sql.add(s);var q=mock(PreparedStatement.class);statements.put(s,q);var r=mock(ResultSet.class);when(q.executeQuery()).thenReturn(r);
  if(s.contains("m.role_code")){when(r.next()).thenReturn(true,false);when(r.getString(1)).thenAnswer(a->role);}
  else if(s.contains("clock_timestamp")){when(r.next()).thenReturn(true,false);when(r.getObject(1,OffsetDateTime.class)).thenReturn(OffsetDateTime.parse("2026-10-23T20:00:00Z"));}
  else if(s.contains("GROUP BY severity")){when(r.next()).thenReturn(true,false);when(r.getString(1)).thenReturn("HIGH");when(r.getLong(2)).thenReturn(123L);}
  else if(s.startsWith("SELECT count(*)")){when(r.next()).thenReturn(true,false);when(r.getLong(1)).thenAnswer(a->population);}
  else if(s.endsWith("LIMIT 51")&&failPage)when(q.executeQuery()).thenThrow(new SQLException("synthetic failure"));
  return q;});}
 Snapshot read(View view,Cursor before){return new PostgresEventOperationalMetricsReader(source,platform).read(new Access(ACTOR,TENANT,1),Window.HOURS_24,view,before);}
 @Test void wholePopulationCountsAreNotDerivedFromReturnedPage()throws Exception{try(var guard=mockStatic(PostgresAuthorization.class)){var result=read(View.CURRENT_PROBLEMS,null);assertEquals(123,result.currentProblemCount());assertTrue(result.items().isEmpty());assertEquals(0,result.mtta().populationCount());verify(connection).setReadOnly(true);verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);verify(connection).setAutoCommit(false);verify(connection).commit();verify(connection,never()).rollback();guard.verify(()->PostgresAuthorization.require(connection,ACTOR,TENANT,1,"dashboard:read"));guard.verify(()->PostgresAuthorization.require(connection,ACTOR,TENANT,1,"tickets:read"));var page=sql.stream().filter(s->s.endsWith("LIMIT 51")).findFirst().orElseThrow();assertTrue(page.contains(PostgresEventOperationalMetricsReader.CURRENT));assertFalse(page.contains("handling_state='NEW'"));for(var q:statements.values())verify(q).setQueryTimeout(10);}}
 @Test void budgetExceededSkipsFullCohortAndDoesNotInventAverage(){population=10001;try(var ignored=mockStatic(PostgresAuthorization.class)){var result=read(View.RECENT_PROBLEMS,null);assertEquals("SAMPLE_BUDGET_EXCEEDED",result.mtta().status());assertNull(result.mtta().statistics());assertTrue(sql.stream().noneMatch(s->s.endsWith("LIMIT 10001")));}}
 @Test void incompleteFullCohortFailsInsteadOfPartialMean()throws Exception{population=1;try(var ignored=mockStatic(PostgresAuthorization.class)){assertEquals(503,assertThrows(DomainException.class,()->read(View.RECENT_PROBLEMS,null)).status);verify(connection).rollback();verify(connection,never()).commit();}}
 @Test void sqlFailureRollsBackAndCannotBecomeEmptySuccess()throws Exception{failPage=true;try(var ignored=mockStatic(PostgresAuthorization.class)){assertEquals(503,assertThrows(DomainException.class,()->read(View.CURRENT_PROBLEMS,null)).status);verify(connection).rollback();verify(connection,never()).commit();}}
 @Test void customerRoleIsRejectedBeforeReadingEvents()throws Exception{role="tadmin";try(var ignored=mockStatic(PostgresAuthorization.class)){assertEquals(403,assertThrows(DomainException.class,()->read(View.CURRENT_PROBLEMS,null)).status);assertTrue(sql.stream().noneMatch(s->s.contains("mop_monitoring_event")));verify(connection).rollback();}}
 @Test void cursorOnlyNarrowsPageAndUsesBoundParameters()throws Exception{var cursor=new Cursor(Instant.parse("2026-10-23T18:00:00Z"),UUID.fromString(ACTOR));try(var ignored=mockStatic(PostgresAuthorization.class)){read(View.RECENT_PROBLEMS,cursor);var query=sql.stream().filter(s->s.endsWith("LIMIT 51")).findFirst().orElseThrow();assertTrue(query.contains(PostgresEventOperationalMetricsReader.COHORT));assertTrue(query.contains("AND (problem_at,id)<(?,?::uuid)"));var q=statements.get(query);verify(q).setObject(4,OffsetDateTime.parse("2026-10-23T18:00:00Z"));verify(q).setString(5,ACTOR);assertTrue(sql.stream().filter(s->s.startsWith("SELECT count(*)")).noneMatch(s->s.contains("(problem_at,id)")));}}
}
