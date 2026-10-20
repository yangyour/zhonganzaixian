package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.ticket.*;
import cn.mop.domain.ticket.TicketLifecycleRepository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** JDBC substitutes test fail-closed control flow only; not real SQL or migration acceptance. */
class TicketSlaPolicyBindingsBoundaryTest {
    static final String TENANT="10000000-0000-0000-0000-000000000001",TICKET="10000000-0000-0000-0000-000000000002",ACTOR="10000000-0000-0000-0000-000000000003",RELEASE="10000000-0000-0000-0000-000000000004";
    static final Access ACCESS=new Access(ACTOR,TENANT,1,"synthetic");
    final ObjectMapper mapper=new ObjectMapper();
    Entry entry(String json){return new Entry(TICKET,new TicketLifecycle.Snapshot(TENANT,TicketLifecycle.State.NEW,1,0,Instant.parse("2026-10-20T00:00:00Z"),null,null,null,null,null,null),null,List.of(),json);}
    String frozen(){return "{\"configuration_release_ref\":\"sha256:"+"a".repeat(64)+"\",\"sla_opening\":{\"configuration_inputs\":{\"sla.reopen_and_mttr_policy\":{\"value\":{\"clock_policy\":\"EXCLUDE_CONFIRMED_PAUSES\"}}}}}";}
    @Test void existingOpeningPolicyNeedsNoNewTable(){Connection c=mock(Connection.class);var view=new PostgresTicketSlaPolicyBindings(mapper);try{assertEquals("OPENING_FROZEN",view.view(c,ACCESS,entry(frozen())).status());}catch(SQLException e){throw new AssertionError(e);}verifyNoInteractions(c);}
    @Test void legacyMissingClockDoesNotPretendBindable()throws Exception{Connection c=mock(Connection.class);assertEquals("OPENING_SNAPSHOT_MISSING",new PostgresTicketSlaPolicyBindings(mapper).view(c,ACCESS,entry("{}")).missing());verifyNoInteractions(c);}
    @Test void missingV35RemainsConfigurationPending()throws Exception{Connection c=mock(Connection.class);PreparedStatement s=mock(PreparedStatement.class);ResultSet rs=mock(ResultSet.class);when(c.prepareStatement(anyString())).thenReturn(s);when(s.executeQuery()).thenReturn(rs);when(rs.next()).thenReturn(true);when(rs.getBoolean(1)).thenReturn(false);assertEquals("V35_REQUIRED",new PostgresTicketSlaPolicyBindings(mapper).view(c,ACCESS,entry("{\"sla_opening\":{}}")).missing());}
    @Test void unknownOrNullClockPolicyDoesNotBecomeDefault()throws Exception{for(String json:List.of("null","{}","{\"clock_policy\":null}","{\"clock_policy\":\"default\"}"))assertNull(PostgresTicketSlaPolicyBindings.policy(mapper.readTree(json)));}
    @Test void frozenPolicyCannotBeRebound()throws Exception{Connection c=mock(Connection.class);when(c.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));assertEquals(422,assertThrows(DomainException.class,()->new PostgresTicketSlaPolicyBindings(mapper).bind(c,ACCESS,entry(frozen()),RELEASE,1)).status);verify(c,never()).prepareStatement(startsWith("INSERT"));}
    @Test void stalePublicationVersionCannotBeBound()throws Exception{
        Connection c=mock(Connection.class);when(c.prepareStatement(anyString())).thenAnswer(inv->{String sql=inv.getArgument(0);PreparedStatement s=mock(PreparedStatement.class);ResultSet r=mock(ResultSet.class);when(s.executeQuery()).thenReturn(r);when(r.next()).thenReturn(!sql.startsWith("SELECT b.*"));when(r.getBoolean(1)).thenReturn(true);when(r.getObject(1,OffsetDateTime.class)).thenReturn(OffsetDateTime.parse("2026-10-20T01:00:00Z"));when(r.getString("id")).thenReturn(RELEASE);when(r.getLong("version")).thenReturn(2L);when(r.getString("action")).thenReturn("PUBLISH");when(r.getString("value_json")).thenReturn("{\"clock_policy\":\"EXCLUDE_CONFIRMED_PAUSES\"}");return s;});
        assertEquals("CONFIG_RELEASE_CONFLICT",assertThrows(DomainException.class,()->new PostgresTicketSlaPolicyBindings(mapper).bind(c,ACCESS,entry("{\"sla_opening\":{}}"),RELEASE,1)).code);verify(c,never()).prepareStatement(startsWith("INSERT"));
    }
}
