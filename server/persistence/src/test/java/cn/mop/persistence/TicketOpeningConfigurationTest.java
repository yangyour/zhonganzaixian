package cn.mop.persistence;
import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TicketOpeningConfigurationTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private static final Instant OPEN=Instant.parse("2026-10-20T00:00:00Z");
    private Map<String,TicketOpeningConfiguration.Value> values(){
        var values=new LinkedHashMap<String,TicketOpeningConfiguration.Value>();
        put(values,"tenant.plan","标准");put(values,"ticket.solution_min_chars",20);put(values,"ticket.reopen_window_hours",72);put(values,"ticket.reopen_manager_threshold",2);
        put(values,"ticket.customer_confirm_timeout_hours",Map.of("基础",48,"标准",48,"进阶",24));
        put(values,"sla.p1_response_min",Map.of("基础",30,"标准",10,"进阶",10));put(values,"sla.p1_recover_min",Map.of("基础",240,"标准",120,"进阶",90));
        put(values,"sla.p3_response_min",Map.of("基础",240,"标准",120,"进阶",60));put(values,"sla.advanced_p3_recover_min",240);
        return values;
    }
    private void put(Map<String,TicketOpeningConfiguration.Value> values,String key,Object value){values.put(key,new TicketOpeningConfiguration.Value(mapper.valueToTree(value),"test-publication:"+key));}
    private TicketOpeningConfiguration.Plan resolve(Map<String,TicketOpeningConfiguration.Value> values,String priority){return new TicketOpeningConfiguration(values,mapper).resolve(OPEN,priority);}
    @Test void standardPlanUsesExplicitPublishedBudgetsAndFreezesProvenance()throws Exception{
        var plan=resolve(values(),"P1");assertEquals(OPEN.plusSeconds(600),plan.responseDue());assertEquals(OPEN.plusSeconds(7200),plan.recoveryDue());
        var json=mapper.readTree(plan.json());assertEquals("STANDARD",json.path("service_tier").textValue());assertTrue(json.path("configuration_release_ref").textValue().matches("sha256:[0-9a-f]{64}"));assertEquals(OPEN.toString(),json.path("sla_opening").path("opened_at").textValue());assertTrue(json.path("sla_opening").path("configuration_inputs").has("tenant.plan"));
    }
    @Test void missingTierIsPendingWithoutAnyDefault(){var v=values();v.remove("tenant.plan");var e=assertThrows(DomainException.class,()->resolve(v,"P1"));assertEquals("CONFIG_PENDING",e.code);assertTrue(e.details.toString().contains("tenant.plan"));}
    @Test void missingPublishedBudgetNeverFallsBackToContractDefault(){var v=values();v.remove("sla.p1_response_min");assertThrows(DomainException.class,()->resolve(v,"P1"));}
    @Test void fixedConstraintsCannotBeOverridden(){var v=values();put(v,"ticket.reopen_window_hours",96);assertThrows(DomainException.class,()->resolve(v,"P1"));}
    @Test void basicTierDoesNotUseContinuousTimeWhenCalendarMissing(){var v=values();put(v,"tenant.plan","基础");assertThrows(DomainException.class,()->resolve(v,"P1"));}
    @Test void basicTierUsesOnlyExplicitWorkingWindows(){var v=values();put(v,"tenant.plan","基础");put(v,"sla.tenant_work_calendar",Map.of("timezone","Asia/Shanghai","complete",true,"coverage_start",OPEN.toString(),"coverage_end","2026-10-21T00:00:00Z","working_windows",List.of(Map.of("start","2026-10-20T01:00:00Z","end","2026-10-20T09:00:00Z"))));var plan=resolve(v,"P1");assertEquals(Instant.parse("2026-10-20T01:30:00Z"),plan.responseDue());assertEquals(Instant.parse("2026-10-20T05:00:00Z"),plan.recoveryDue());}
    @Test void standardP3NeedsExplicitNextDayRule(){assertThrows(DomainException.class,()->resolve(values(),"P3"));}
    @Test void standardP3UsesExplicitCalendarDayCutoff(){var v=values();put(v,"sla.basic_standard_p3_next_day",Map.of("kind","CALENDAR_DAY","cutoff","18:00","timezone","Asia/Shanghai"));assertEquals(Instant.parse("2026-10-21T10:00:00Z"),resolve(v,"P3").recoveryDue());}
    @Test void advancedP3UsesItsPublishedMinuteBudget(){var v=values();put(v,"tenant.plan","进阶");assertEquals(OPEN.plusSeconds(14400),resolve(v,"P3").recoveryDue());}
    @Test void fractionalMillisecondsAndNegativeDurationsArePending(){for(Object minutes:List.of(-1,0,0.000001)){var v=values();put(v,"sla.p1_response_min",Map.of("标准",minutes));assertThrows(DomainException.class,()->resolve(v,"P1"));}}
    @Test void unknownCalendarFieldsCannotPretendKnownHolidays(){var v=values();put(v,"tenant.plan","基础");put(v,"sla.tenant_work_calendar",Map.of("weekday_default","9-18"));assertThrows(DomainException.class,()->resolve(v,"P1"));}
    @Test void missingSourceReferenceIsNotPublished(){var v=values();v.put("tenant.plan",new TicketOpeningConfiguration.Value(mapper.valueToTree("标准"),null));assertThrows(DomainException.class,()->resolve(v,"P1"));}
}
