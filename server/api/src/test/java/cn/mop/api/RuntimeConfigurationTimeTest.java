package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.configuration.RuntimeConfigurationRepository;
import cn.mop.domain.configuration.RuntimeConfigurationRepository.Command;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Parser/dispatch boundaries; mock repository does not certify schedule eligibility or publication. */
class RuntimeConfigurationTimeTest {
    static final String ACTOR="78000000-0000-4000-8000-000000000001",TENANT="78000000-0000-4000-8000-000000000002",REVISION="78000000-0000-4000-8000-000000000003",KEY="remote.idle_timeout_sec";
    final ObjectMapper mapper=new ObjectMapper();
    PlatformRepository platform; RuntimeConfigurationRepository repository; RuntimeConfigurationCatalog catalog; RuntimeConfigurationController controller;
    @BeforeEach void setup(){
        platform=mock(PlatformRepository.class);repository=mock(RuntimeConfigurationRepository.class);catalog=new RuntimeConfigurationCatalog(mapper);controller=new RuntimeConfigurationController(platform,repository,catalog,mapper);
        when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,true)));
        when(platform.configurationRevision(TENANT,REVISION)).thenReturn(Optional.of(Map.of("configuration_key",KEY,"proposed_value",120)));
        when(platform.idempotentAuthorized(any(),any(),any(),any(),any(),anyLong(),any(),any())).thenAnswer(call->{((Runnable)call.getArgument(6)).run();return ((Supplier<?>)call.getArgument(7)).get();});
        when(repository.publish(any(),any(),any(),any())).thenReturn(Map.of("scope","MOCK_PARSER_ONLY"));
    }
    MockHttpServletRequest request(){var r=new MockHttpServletRequest("POST","/api/v1/runtime-configurations/"+KEY+"/releases");r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);r.setAttribute("mop.trace_id","runtime-time-test");r.addHeader("Idempotency-Key","runtime-time-test-key");return r;}
    ObjectNode input(){return mapper.createObjectNode().put("expected_version",0).put("expected_definition_hash",catalog.hash(KEY)).put("action","PUBLISH").put("revision_id",REVISION).put("revision_version",1).putNull("target_release_id").put("activation","SCHEDULED").put("effective_at","2026-10-22T08:00:00Z").putNull("expires_at").put("reason","SYNTHETIC_TIME_PARSER_ONLY");}
    void rejects(String field,String value){assertEquals(400,assertThrows(DomainException.class,()->controller.publish(KEY,input().put(field,value),request())).status);verify(platform,never()).idempotentAuthorized(any(),any(),any(),any(),any(),anyLong(),any(),any());verifyNoInteractions(repository);}
    @Test void rejectsMidnightTwentyFourInsteadOfMovingToNextDay(){rejects("effective_at","2026-10-22T24:00:00Z");}
    @Test void rejectsLeapSecondInsteadOfMovingToPreviousSecond(){rejects("effective_at","2026-10-22T23:59:60Z");}
    @Test void expiryUsesTheSameStrictClock(){for(String v:List.of("2026-10-23T24:00:00Z","2026-10-23T23:59:60Z"))rejects("expires_at",v);}
    @Test void rejectsImpossibleDatesAndUnapprovedPrecision(){for(String v:List.of("2026-02-30T08:00:00Z","2026-02-29T08:00:00Z","2026-04-31T08:00:00Z","0000-01-01T00:00:00Z","2026-10-22T08:00:00.1234Z","2026-10-22T08:00:00+08:00","2026-10-22"))rejects("effective_at",v);}
    @Test void validLeapDayAndBoundaryInstantsReachRepositoryWithoutTimeShift(){for(String v:List.of("0001-01-01T00:00:00Z","2024-02-29T08:00:00Z","2026-10-22T08:00:00.1Z","2026-10-22T08:00:00.12Z","2026-10-22T08:00:00.123Z","9999-12-31T23:59:59.999Z")){controller.publish(KEY,input().put("effective_at",v),request());var command=ArgumentCaptor.forClass(Command.class);verify(repository).publish(any(),eq(KEY),command.capture(),any());assertEquals(Instant.parse(v),command.getValue().effectiveAt());clearInvocations(repository);}}
    @Test void validExplicitExpiryPreservesMillisecondInstant(){controller.publish(KEY,input().put("expires_at","2026-10-23T08:00:00.123Z"),request());var command=ArgumentCaptor.forClass(Command.class);verify(repository).publish(any(),eq(KEY),command.capture(),any());assertEquals(Instant.parse("2026-10-23T08:00:00.123Z"),command.getValue().expiresAt());}
    @Test void immediateActivationStillRequiresExplicitNullTime(){controller.publish(KEY,input().put("activation","IMMEDIATE").putNull("effective_at"),request());var command=ArgumentCaptor.forClass(Command.class);verify(repository).publish(any(),eq(KEY),command.capture(),any());assertNull(command.getValue().effectiveAt());}
}
