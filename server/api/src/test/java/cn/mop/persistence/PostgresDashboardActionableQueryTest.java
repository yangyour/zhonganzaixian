package cn.mop.persistence;

import cn.mop.domain.dashboard.DashboardPendingTicketsReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Production JDBC boundary with instrumented rows; not a PostgreSQL execution claim. */
class PostgresDashboardActionableQueryTest {
    @Test void totalsAndTopFiveUseUnresolvedPredicateInsideOneReadSnapshot() throws Exception {
        String actor="79000000-0000-4000-8000-000000000001",tenant="79000000-0000-4000-8000-000000000002";
        var source=mock(DataSource.class);var connection=mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        List<String> statements=new ArrayList<>();
        when(connection.prepareStatement(anyString())).thenAnswer(call->{
            String sql=call.getArgument(0);statements.add(sql);
            var statement=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
            when(statement.executeQuery()).thenReturn(rows);
            if(sql.contains("mop_effective_capabilities")) {
                when(rows.next()).thenReturn(true);var array=mock(java.sql.Array.class);
                when(rows.getArray(1)).thenReturn(array);when(array.getArray()).thenReturn(new String[]{"dashboard:read","tickets:read"});
            } else if(sql.contains("SELECT permission_version")) {
                when(rows.next()).thenReturn(true);when(rows.getLong(1)).thenReturn(7L);
            } else if(sql.startsWith("SELECT m.role_code")) {
                when(rows.next()).thenReturn(true);when(rows.getString(1)).thenReturn("padmin");
            } else if(sql.contains("GROUP BY model,state")) {
                int[] index={-1};when(rows.next()).thenAnswer(c->++index[0]<3);
                when(rows.getString("model")).thenReturn("WORKFLOW_V33");
                when(rows.getString("state")).thenAnswer(c->List.of("OPEN","RESOLVED","CLOSED").get(index[0]));
                when(rows.getLong("count")).thenAnswer(c->List.of(6L,9L,2L).get(index[0]));
            } else if(sql.contains("FROM visible")) {
                assertTrue(sql.contains("WHERE "+PostgresDashboardPendingTicketsReader.predicate(true)));
                assertTrue(sql.contains("ORDER BY created_at DESC,id DESC LIMIT 5"));
                int[] index={-1};when(rows.next()).thenAnswer(c->++index[0]<5);
                when(rows.getString("id")).thenAnswer(c->String.format("79000000-0000-4000-8000-%012d",100-index[0]));
                when(rows.getString("model")).thenReturn("WORKFLOW_V33");when(rows.getString("state")).thenReturn("OPEN");
                when(rows.getString("receipt_no")).thenReturn("SYNTHETIC");when(rows.getString("title")).thenReturn("unit only");
                when(rows.getLong("version")).thenReturn(1L);
                when(rows.getObject("created_at",OffsetDateTime.class)).thenReturn(OffsetDateTime.parse("2026-10-23T10:00:00Z"));
            } else throw new AssertionError(sql);
            return statement;
        });
        var clock=mock(Statement.class);var time=mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(clock);when(clock.executeQuery(anyString())).thenReturn(time);
        when(time.next()).thenReturn(true);when(time.getObject(1,OffsetDateTime.class)).thenReturn(OffsetDateTime.parse("2026-10-23T11:00:00Z"));
        var platform=new PostgresPlatformRepository(source,new ObjectMapper());
        var reader=new PostgresDashboardPendingTicketsReader(source,platform);
        var result=reader.readSnapshot(new DashboardPendingTicketsReader.Access(actor,tenant,7),true,(c,s)->{
            assertSame(connection,c);return s;
        });
        assertEquals(17,result.registrations());assertEquals(6,result.knownPending());assertEquals(11,result.terminal());
        assertEquals(5,result.items().size());
        verify(connection).setReadOnly(true);verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        verify(connection).commit();verify(source,times(1)).getConnection();
        assertFalse(PostgresDashboardPendingTicketsReader.predicate(false).contains("RESOLVED"));
        assertTrue(statements.stream().filter(s->s.startsWith("WITH visible")).allMatch(s->s.contains("t.tenant_id=?::uuid AND (? OR t.reporter_id=?::uuid)")));
    }
}
