package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.sla.SlaClockState;
import cn.mop.domain.ticket.TicketLifecycleRepository.SlaEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Mock transaction/projection checks are not PostgreSQL concurrency or delivery evidence. */
class SlaReminderRepositoryTest {
    static final String TENANT="10000000-0000-0000-0000-000000000001",TICKET="10000000-0000-0000-0000-000000000002";
    static final Instant T=Instant.parse("2026-10-20T00:00:00Z"),NOW=T.plusSeconds(80);
    static final PostgresSlaReminderRepository.Claim CLAIM=new PostgresSlaReminderRepository.Claim(TENANT,TICKET,1,"test-worker",1,1);
    static SlaEntry projection(String tenant,long responseBudget){
        var response=new SlaClockState.Clock(SlaClockState.Status.RUNNING,T.plusSeconds(responseBudget),null,Duration.ofSeconds(80),Duration.ofSeconds(responseBudget),false,null);
        var recovery=new SlaClockState.Clock(SlaClockState.Status.RUNNING,T.plusSeconds(1000),null,Duration.ofSeconds(80),Duration.ofSeconds(1000),false,null);
        return new SlaEntry(tenant,TICKET,"STANDARD","P1",null,null,new SlaClockState.Projection(NOW,1,response,recovery,null,List.of(),"sha256:"+"a".repeat(64)));
    }
    static class Harness {
        final DataSource ds=mock(DataSource.class);final Connection c=mock(Connection.class);final ObjectMapper mapper=new ObjectMapper();
        final PostgresPlatformRepository platform;final PreparedStatement job=mock(PreparedStatement.class),registry=mock(PreparedStatement.class),clock=mock(PreparedStatement.class),recorded=mock(PreparedStatement.class),ledger=mock(PreparedStatement.class),intent=mock(PreparedStatement.class),finish=mock(PreparedStatement.class),lock=mock(PreparedStatement.class);
        final ResultSet jobRows=mock(ResultSet.class),registryRows=mock(ResultSet.class),clockRows=mock(ResultSet.class),priorRows=mock(ResultSet.class);
        Harness(boolean actualTransaction)throws Exception{
            platform=actualTransaction?new PostgresPlatformRepository(ds,mapper):mock(PostgresPlatformRepository.class);
            when(ds.getConnection()).thenReturn(c);
            if(!actualTransaction){when(platform.ticketJobTransaction(any())).thenAnswer(i->i.<Supplier<?>>getArgument(0).get());when(platform.ticketJobConnection()).thenReturn(c);}
            when(c.prepareStatement(anyString())).thenAnswer(i->{String sql=i.getArgument(0);if(sql.startsWith("SELECT * FROM public.mop_ticket_sla_scan_job"))return job;if(sql.startsWith("SELECT key,registry_json"))return registry;if(sql.startsWith("SELECT date_trunc"))return clock;if(sql.startsWith("SELECT clock,progress"))return recorded;if(sql.startsWith("INSERT INTO public.mop_ticket_sla_reminder"))return ledger;if(sql.startsWith("INSERT INTO public.mop_sla_notification_intent"))return intent;if(sql.startsWith("UPDATE public.mop_ticket_sla_scan_job"))return finish;return lock;});
            when(job.executeQuery()).thenReturn(jobRows);when(jobRows.next()).thenReturn(true);when(jobRows.getString("status")).thenReturn("LEASED");when(jobRows.getString("lease_owner")).thenReturn("test-worker");when(jobRows.getLong("lifecycle_version")).thenReturn(1L);when(jobRows.getLong("fencing_token")).thenReturn(1L);when(jobRows.getInt("attempts")).thenReturn(1);when(jobRows.getObject("lease_until",OffsetDateTime.class)).thenReturn(NOW.plusSeconds(40).atOffset(ZoneOffset.UTC));
            when(registry.executeQuery()).thenReturn(registryRows);when(registryRows.next()).thenReturn(true,true,false);when(registryRows.getString(1)).thenReturn("sla.tick_interval_sec","sla.reminder_progress");when(registryRows.getString(2)).thenReturn("{\"contract_value_kind\":\"fixed_constraint\",\"value\":30}","{\"contract_value_kind\":\"fixed_constraint\",\"value\":[0.5,0.8,1.0]}");
            when(clock.executeQuery()).thenReturn(clockRows);when(clockRows.next()).thenReturn(true);when(clockRows.getObject(1,OffsetDateTime.class)).thenReturn(NOW.atOffset(ZoneOffset.UTC));
            when(recorded.executeQuery()).thenReturn(priorRows);when(priorRows.next()).thenReturn(false);when(ledger.executeUpdate()).thenReturn(1);when(intent.executeUpdate()).thenReturn(1);when(finish.executeUpdate()).thenReturn(1);
        }
        PostgresSlaReminderRepository repository(SlaEntry value){return new PostgresSlaReminderRepository(ds,platform,mapper,c->value);}
    }
    @Test void dueNodesCreateIntentsNotDelivery()throws Exception{
        var h=new Harness(false);assertTrue(h.repository(projection(TENANT,100)).scan(CLAIM,3));verify(h.ledger,times(2)).executeUpdate();verify(h.intent,times(2)).executeUpdate();verify(h.platform).auditSlaWorker(eq(TENANT),eq(TICKET),eq("SUCCEEDED"),eq("RECORDED_NOT_DELIVERED_2"),anyString());verify(h.finish).setInt(3,0);
        verify(h.intent,times(2)).setString(eq(5),argThat(s->s.contains("\"escalation_required\":false")&&!s.contains("recipient")&&!s.contains("DELIVERED")));
    }
    @Test void duplicateLedgerConflictDoesNotCreateOrphanIntent()throws Exception{
        var h=new Harness(false);when(h.ledger.executeUpdate()).thenReturn(0);assertTrue(h.repository(projection(TENANT,100)).scan(CLAIM,3));verify(h.intent,never()).executeUpdate();verify(h.platform,never()).auditSlaWorker(any(),any(),any(),any(),any());
    }
    @Test void nothingDueOnlyReschedules()throws Exception{
        var h=new Harness(false);assertTrue(h.repository(projection(TENANT,1000)).scan(CLAIM,3));verify(h.ledger,never()).executeUpdate();verify(h.intent,never()).executeUpdate();verify(h.finish).setObject(2,NOW.plusSeconds(30).atOffset(ZoneOffset.UTC));
    }
    @Test void intentFailureRollsBackLedgerAndCannotCommit()throws Exception{
        var h=new Harness(true);when(h.intent.executeUpdate()).thenThrow(new SQLException("synthetic intent failure"));assertEquals(503,assertThrows(DomainException.class,()->h.repository(projection(TENANT,100)).scan(CLAIM,3)).status);verify(h.c).rollback();verify(h.c,never()).commit();
    }
    @Test void staleFenceCannotReadProjectionOrWriteEvidence()throws Exception{
        var h=new Harness(false);when(h.jobRows.getLong("fencing_token")).thenReturn(2L);assertFalse(h.repository(null).scan(CLAIM,3));verify(h.registry,never()).executeQuery();verify(h.ledger,never()).executeUpdate();
    }
    @Test void crossTenantProjectionFailsBeforeRecording()throws Exception{
        var h=new Harness(true);assertThrows(DomainException.class,()->h.repository(projection("10000000-0000-0000-0000-000000000099",100)).scan(CLAIM,3));verify(h.c).rollback();verify(h.ledger,never()).executeUpdate();
    }
    @Test void invalidOperationalInputsNeverConnect()throws Exception{
        DataSource source=mock(DataSource.class);var r=new PostgresSlaReminderRepository(source,new PostgresPlatformRepository(source,new ObjectMapper()),new ObjectMapper());assertThrows(IllegalArgumentException.class,()->r.claim("",30));assertThrows(IllegalArgumentException.class,()->r.claim("worker",0));assertThrows(IllegalArgumentException.class,()->r.scan(CLAIM,0));verifyNoInteractions(source);
    }
    @Test void fixedRegistryMissingDoesNotInventProgressNodes()throws Exception{
        var h=new Harness(true);when(h.registryRows.next()).thenReturn(false);assertThrows(DomainException.class,()->h.repository(projection(TENANT,100)).scan(CLAIM,3));verify(h.c).rollback();verify(h.ledger,never()).executeUpdate();
    }
}
