package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.dashboard.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pure race-window checks, not evidence of a live PostgreSQL transaction. */
class DashboardReadAuthorizationTest {
    static final String ACTOR="79000000-0000-4000-8000-000000000001";
    static final String TENANT="79000000-0000-4000-8000-000000000002";
    @ParameterizedTest
    @ValueSource(strings={"v1:inactive","v1:removed","v1:version","v1:capability","v1:role","v1:tenant","v1:session",
        "v2:inactive","v2:removed","v2:version","v2:capability","v2:role","v2:tenant","v2:session"})
    void rejectsChangesDuringRead(String scenario) {
        var platform=mock(PlatformRepository.class);
        var member=new Membership(ACTOR,TENANT,"padmin",1,true);
        when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(member));
        var request=new MockHttpServletRequest();
        request.setUserPrincipal(()->ACTOR);
        request.getSession().setAttribute("mop.tenant_id",TENANT);
        var base=new DashboardPendingTicketsReader.Snapshot(UUID.fromString(TENANT),UUID.fromString(ACTOR),1,
            Instant.parse("2026-10-23T16:00:00Z"),"CURRENT_TENANT_INTERNAL",0,0,0,0,List.of());
        Runnable change=()->{
            switch(scenario.split(":")[1]) {
                case "inactive" -> when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",1,false)));
                case "removed" -> when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.empty());
                case "version" -> when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",2,true)));
                case "capability" -> when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",1,true,List.of("dashboard:read"))));
                case "role" -> when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",1,true)));
                case "tenant" -> request.getSession().setAttribute("mop.tenant_id","79000000-0000-4000-8000-000000000003");
                case "session" -> request.getSession().invalidate();
                default -> throw new AssertionError();
            }
        };
        DomainException error;
        if(scenario.startsWith("v1")) {
            DashboardPendingTicketsReader reader=access->{change.run();return base;};
            error=assertThrows(DomainException.class,()->new DashboardPendingTicketsController(platform,reader).read(request));
        } else {
            DashboardPendingTicketSlaReader reader=access->{change.run();return new DashboardPendingTicketSlaReader.Snapshot(base,0,0,0,List.of());};
            error=assertThrows(DomainException.class,()->new DashboardPendingTicketSlaController(platform,reader).read(request));
        }
        assertEquals(401,error.status);
        assertEquals("SESSION_EXPIRED",error.code);
    }
}
