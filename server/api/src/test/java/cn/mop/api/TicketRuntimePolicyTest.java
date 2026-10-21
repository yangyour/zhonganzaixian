package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.configuration.RuntimeConfigurationRepository;
import cn.mop.domain.ticket.*;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TicketRuntimePolicyTest {
    final ObjectMapper mapper=new ObjectMapper();final RuntimeConfigurationCatalog catalog=new RuntimeConfigurationCatalog(mapper);
    final RuntimeConfigurationRepository runtime=mock(RuntimeConfigurationRepository.class);
    final TicketRuntimePolicy policy=new TicketRuntimePolicy(runtime,catalog,mapper);
    final TicketWorkflowRepository.Access access=new TicketWorkflowRepository.Access("tenant","actor",7,"trace");
    final Instant at=Instant.parse("2026-10-16T08:00:00Z");
    List<Map<String,Object>> rows;
    @BeforeEach void setup(){rows=new ArrayList<>();for(String key:RuntimeConfigurationRepository.TICKET_KEYS){var row=new LinkedHashMap<String,Object>();row.put("tenant_id","tenant");row.put("configuration_key",key);row.put("source","postgresql");row.put("observed_at",at.toString());row.put("status","UNCONFIGURED");row.put("selected_release",null);row.put("effective_value",null);rows.add(row);}when(runtime.ticketInputs(any())).thenAnswer(x->rows);}
    Map<String,Object> row(String key){return rows.stream().filter(r->r.get("configuration_key").equals(key)).findFirst().orElseThrow();}
    void publish(String key,Object value){var r=row(key);r.put("status","ACTIVE");r.put("effective_value",value);r.put("selected_release",Map.of("id","release","version",3,"definition_hash",catalog.hash(key)));}
    @Test void standardUsesActualContractDefaultsNotNotionExamples(){var s=policy.freeze(access,"P1",Tier.STANDARD,at);assertEquals(at.plusSeconds(600),s.responseDueAt());assertEquals(at.plusSeconds(7200),s.recoveryDueAt());assertEquals(48,s.confirmationHours());}
    @Test void basicDoesNotUseDefaultSevenByTwentyFourWithoutCalendar(){var s=policy.freeze(access,"P1",Tier.BASIC,at);assertNull(s.responseDueAt());assertNull(s.recoveryDueAt());assertEquals("CALENDAR_MISSING",s.missing().get("response"));}
    @Test void approvedPublicationIsFrozenWithVersionAndDefinition(){publish("sla.p1_response_min",Map.of("标准",15));var s=policy.freeze(access,"P1",Tier.STANDARD,at);assertEquals(at.plusSeconds(900),s.responseDueAt());assertTrue(s.references().stream().anyMatch(r->r.key().equals("sla.p1_response_min")&&r.version()==3&&r.source().equals("RUNTIME_RELEASE")));}
    @Test void disabledExpiredAndChangedDefinitionsNeverUseDefault(){for(String status:List.of("DISABLED","EXPIRED","ACTIVE")){publish("sla.p1_response_min",Map.of("标准",15));row("sla.p1_response_min").put("status",status);if(status.equals("ACTIVE"))row("sla.p1_response_min").put("selected_release",Map.of("definition_hash","b".repeat(64)));var s=policy.freeze(access,"P1",Tier.STANDARD,at);assertNull(s.responseDueAt());}}
    @Test void invalidTierValueDoesNotBorrowAnotherTierOrFourHours(){publish("sla.p1_response_min",Map.of("基础",30));var s=policy.freeze(access,"P1",Tier.STANDARD,at);assertNull(s.responseDueAt());assertEquals("TIER_VALUE_MISSING",s.missing().get("response"));}
    @Test void missingNextDayStaysUnknownButAdvancedHasDefinedDuration(){assertNull(policy.freeze(access,"P3",Tier.STANDARD,at).recoveryDueAt());assertEquals(at.plusSeconds(14400),policy.freeze(access,"P3",Tier.ADVANCED,at).recoveryDueAt());}
    @Test void explicitNextDayUsesLocalDateAndCutoff(){publish("sla.basic_standard_p3_next_day",Map.of("timezone","Asia/Shanghai","day_offset",1,"deadline_local_time","17:00","day_kind","CALENDAR"));assertEquals(Instant.parse("2026-10-17T09:00:00Z"),policy.freeze(access,"P3",Tier.STANDARD,at).recoveryDueAt());}
    @Test void invalidOrAmbiguousDstCutoffDoesNotSilentlyShiftDeadline(){var node=mapper.valueToTree(Map.of("timezone","America/New_York","day_offset",1,"deadline_local_time","01:30","day_kind","CALENDAR"));assertThrows(DomainException.class,()->TicketRuntimePolicy.nextDay(node,Instant.parse("2026-10-31T12:00:00Z"),null));}
    @Test void calendarPublicationIsActualImmutableWindowInput(){publish("sla.tenant_work_calendar",Map.of("calendar_id","calendar-test","version",2,"coverage_start",at.toString(),"coverage_end",at.plusSeconds(86400).toString(),"complete",true,"working_windows",List.of(Map.of("start",at.toString(),"end",at.plusSeconds(28800).toString()))));var s=policy.freeze(access,"P1",Tier.BASIC,at);assertEquals(at.plusSeconds(1800),s.responseDueAt());assertEquals(at.plusSeconds(14400),s.recoveryDueAt());}
    @Test void externalAuthorityCannotPretendToTransitionLocally(){publish("itsm.local_authority_mode","EXTERNAL");assertEquals(422,assertThrows(DomainException.class,()->policy.freeze(access,"P1",Tier.STANDARD,at)).status);}
    @Test void mixedTenantOrObservationIsUnavailableNotFallback(){row("sla.p1_response_min").put("tenant_id","other");assertEquals(503,assertThrows(DomainException.class,()->policy.freeze(access,"P1",Tier.STANDARD,at)).status);row("sla.p1_response_min").put("tenant_id","tenant");row("sla.p1_response_min").put("observed_at",at.plusSeconds(1).toString());assertEquals(503,assertThrows(DomainException.class,()->policy.freeze(access,"P1",Tier.STANDARD,at)).status);}
    @Test void failedConfigurationReadPropagates503(){when(runtime.ticketInputs(any())).thenThrow(new DomainException(503,"UPSTREAM_UNAVAILABLE","unavailable"));assertEquals(503,assertThrows(DomainException.class,()->policy.freeze(access,"P1",Tier.STANDARD,at)).status);}
    @Test void confirmationDisabledPreventsDurableAutocloseDeadline(){publish("ticket.customer_confirm_timeout_hours",Map.of("标准",48));row("ticket.customer_confirm_timeout_hours").put("status","DISABLED");assertNull(policy.freeze(access,"P1",Tier.STANDARD,at).confirmationHours());}
}
