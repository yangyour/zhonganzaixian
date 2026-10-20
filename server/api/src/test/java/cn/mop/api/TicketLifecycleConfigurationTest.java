package cn.mop.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class TicketLifecycleConfigurationTest {
    @Test void absentConfigurationKeepsFeatureDisabled(){
        assertFalse(TicketLifecycleConfiguration.enabled(new MockEnvironment()));
    }
    @Test void explicitTrueAndFalseAreTheOnlyAcceptedValues(){
        assertTrue(TicketLifecycleConfiguration.enabled(new MockEnvironment().withProperty("MOP_TICKET_LIFECYCLE_ENABLED","true")));
        assertFalse(TicketLifecycleConfiguration.enabled(new MockEnvironment().withProperty("MOP_TICKET_LIFECYCLE_ENABLED","false")));
    }
    @Test void typosAndAmbiguousValuesCannotSilentlyActivateOrHideMisconfiguration(){
        for(String value:new String[]{"","TRUE","yes","1"," true ","null"})
            assertThrows(IllegalArgumentException.class,()->TicketLifecycleConfiguration.enabled(
                    new MockEnvironment().withProperty("MOP_TICKET_LIFECYCLE_ENABLED",value)));
    }
}
