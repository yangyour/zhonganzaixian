package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.NotificationControlStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationRoutingReadinessTest {
    @Test
    void disabledCandidateDoesNotRequireV51OrTouchExistingRuntime() {
        var store = mock(NotificationControlStore.class);
        assertTrue(new NotificationRoutingConfiguration.NotificationRoutingProbe(false, store).ready());
        verifyNoInteractions(store);
    }

    @Test
    void explicitlyEnabledMissingSchemaFailsApplicationReadiness() {
        var store = mock(NotificationControlStore.class);
        var controller = new PlatformController(mock(PlatformRepository.class), new ObjectMapper(), null, null, null);
        ReflectionTestUtils.setField(controller, "notificationRouting", new NotificationRoutingConfiguration.NotificationRoutingProbe(true, store));
        var failure = assertThrows(DomainException.class, () -> controller.ready(new MockHttpServletRequest()));
        assertEquals(503, failure.status);
        verify(store).ready();
    }
}
