package cn.mop.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.mop.domain.*;
import cn.mop.domain.reporting.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Current-tenant management authorization, strict query and response revocation boundaries. */
class ReportReadershipControllerTest {

    private final String actor = ReportPublicationTest.AUTHOR.toString();
    private final String tenant = ReportPublicationTest.TENANT.toString();
    private final PlatformRepository platform = mock(PlatformRepository.class);
    private final ReportPublicationRepository reports = mock(ReportPublicationRepository.class);
    private final ReportPublicationController controller = new ReportPublicationController(
        platform,
        reports,
        new ObjectMapper().findAndRegisterModules()
    );

    private MockHttpServletRequest request(String role) {
        var request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> actor);
        request.getSession().setAttribute("mop.tenant_id", tenant);
        when(platform.membership(actor, tenant)).thenReturn(
            Optional.of(new Membership(actor, tenant, role, 7, true))
        );
        return request;
    }

    @Test
    void customerCannotEnumerateOtherRecipients() {
        var request = request("viewer");
        assertEquals(
            403,
            assertThrows(DomainException.class, () -> controller.readership(actor, request)).status
        );
        verifyNoInteractions(reports);
    }

    @Test
    void duplicateUnknownInvalidFilterAndCursorFailBeforeRead() {
        for (var parameters : List.of(
            Map.of("filter", new String[] { "ALL", "UNREAD" }),
            Map.of("filter", new String[] { "all" }),
            Map.of("tenant_id", new String[] { tenant }),
            Map.of("after", new String[] { "bad" })
        )) {
            var request = request("padmin");
            parameters.forEach(request::addParameter);
            assertEquals(
                400,
                assertThrows(DomainException.class, () -> controller.readership(actor, request)).status
            );
        }
        verifyNoInteractions(reports);
    }

    @Test
    void explicitFilterAndCursorRemainBoundToCurrentTenant() {
        var state = ReportPublicationTest.published();
        var request = request("padmin");
        request.addParameter("filter", "UNREAD");
        request.addParameter("after", actor);
        var page = ReportReadership.page(
            state,
            List.of(),
            Map.of(),
            state.updatedAt(),
            ReportReadership.Filter.UNREAD,
            UUID.fromString(actor)
        );
        when(
            reports.readership(
                any(),
                eq(state.document().id().toString()),
                eq(ReportReadership.Filter.UNREAD),
                eq(UUID.fromString(actor))
            )
        ).thenReturn(page);
        assertNotNull(controller.readership(state.document().id().toString(), request));
        verify(platform, times(2)).membership(actor, tenant);
    }

    @Test
    void wrongObjectOrRevokedMembershipNeverReturnsPrivateStatistics() {
        var state = ReportPublicationTest.published();
        var request = request("padmin");
        var page = ReportReadership.page(
            state,
            List.of(),
            Map.of(),
            state.updatedAt(),
            ReportReadership.Filter.ALL,
            null
        );
        when(reports.readership(any(), any(), any(), any())).thenReturn(page);
        assertEquals(
            503,
            assertThrows(DomainException.class, () -> controller.readership(actor, request)).status
        );
        when(reports.readership(any(), any(), any(), any())).thenAnswer(call -> {
            when(platform.membership(actor, tenant)).thenReturn(
                Optional.of(new Membership(actor, tenant, "padmin", 8, true))
            );
            return page;
        });
        assertThrows(DomainException.class, () ->
            controller.readership(state.document().id().toString(), request)
        );
    }
}
