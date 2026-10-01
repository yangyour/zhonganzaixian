package cn.mop.api;

import cn.mop.domain.ConfigurationRevisionWorkflow;
import cn.mop.domain.DomainException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationRevisionWorkflowTest {
    @Test void creatorSubmitsAndWithdrawsWhileAnotherActorReviews() {
        assertEquals("IN_REVIEW", ConfigurationRevisionWorkflow.nextStatus("DRAFT", "creator", "creator", "SUBMIT"));
        for (String state : List.of("DRAFT", "IN_REVIEW"))
            assertEquals("WITHDRAWN", ConfigurationRevisionWorkflow.nextStatus(state, "creator", "creator", "WITHDRAW"));
        assertEquals("APPROVED", ConfigurationRevisionWorkflow.nextStatus("IN_REVIEW", "creator", "reviewer", "APPROVE"));
        assertEquals("REJECTED", ConfigurationRevisionWorkflow.nextStatus("IN_REVIEW", "creator", "reviewer", "REJECT"));
        for (String action : List.of("SUBMIT", "WITHDRAW")) {
            DomainException denied = assertThrows(DomainException.class, () -> ConfigurationRevisionWorkflow.nextStatus("DRAFT", "creator", "reviewer", action));
            assertEquals(403, denied.status);
        }
        for (String action : List.of("APPROVE", "REJECT")) {
            DomainException denied = assertThrows(DomainException.class, () -> ConfigurationRevisionWorkflow.nextStatus("IN_REVIEW", "creator", "creator", action));
            assertEquals(403, denied.status);
        }
    }
    @Test void terminalStatesCannotBeChangedAndDraftCannotBeReviewed() {
        for (String state : List.of("APPROVED", "REJECTED", "WITHDRAWN")) {
            for (String action : List.of("SUBMIT", "WITHDRAW", "APPROVE", "REJECT")) {
                String actor = List.of("SUBMIT", "WITHDRAW").contains(action) ? "creator" : "reviewer";
                DomainException invalid = assertThrows(DomainException.class, () -> ConfigurationRevisionWorkflow.nextStatus(state, "creator", actor, action));
                assertEquals(422, invalid.status);
            }
        }
        assertEquals(422, assertThrows(DomainException.class, () -> ConfigurationRevisionWorkflow.nextStatus("DRAFT", "creator", "reviewer", "APPROVE")).status);
        assertEquals(400, assertThrows(DomainException.class, () -> ConfigurationRevisionWorkflow.nextStatus("DRAFT", "creator", "creator", "PUBLISH")).status);
    }
}
