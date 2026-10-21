package cn.mop.persistence;

import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.ticket.TicketSlaSnapshot;
import cn.mop.domain.ticket.WorkflowLifecycle;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PostgresWorkflowSnapshotMapperTest {
    private static WorkflowLifecycle.Snapshot snapshot(){
        var at=Instant.parse("2026-10-21T00:00:00.123Z");
        var sla=TicketSlaSnapshot.freeze("P1",Tier.STANDARD,at,Duration.ofMinutes(10),Duration.ofHours(2),null,48L,null,List.of(),Map.of());
        return WorkflowLifecycle.open(sla,at);
    }
    @Test void httpSnakeCaseCannotChangeV33SnapshotKeys() throws Exception {
        var http=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var stored=PostgresTicketWorkflowRepository.snapshotMapper(http);
        var json=stored.readTree(stored.writeValueAsString(snapshot()));
        assertTrue(json.has("openedAt"));assertFalse(json.has("opened_at"));
        assertTrue(json.path("sla").has("startedAt"));assertFalse(json.path("sla").has("started_at"));
        assertEquals("2026-10-21T00:00:00.123Z",json.path("openedAt").textValue());
        assertSame(PropertyNamingStrategies.SNAKE_CASE,http.getPropertyNamingStrategy());
    }
    @Test void existingCamelCaseSnapshotsReadIdenticallyAcrossHttpAndWorkerMappers() throws Exception {
        var worker=PostgresTicketWorkflowRepository.snapshotMapper(new ObjectMapper());
        var http=PostgresTicketWorkflowRepository.snapshotMapper(new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
        String json=worker.writeValueAsString(snapshot());
        assertEquals(snapshot(),http.readValue(json,WorkflowLifecycle.Snapshot.class));
        assertEquals(json,http.writeValueAsString(snapshot()));
    }
    @Test void unknownFieldsRemainRejectedRatherThanSilentlyDefaulted() throws Exception {
        var mapper=PostgresTicketWorkflowRepository.snapshotMapper(new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        var json=mapper.valueToTree(snapshot());((com.fasterxml.jackson.databind.node.ObjectNode)json).put("invented_field",true);
        assertThrows(Exception.class,()->mapper.treeToValue(json,WorkflowLifecycle.Snapshot.class));
    }
    @Test void strictDuplicateDetectionIsPreserved() throws Exception {
        var http=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        var mapper=PostgresTicketWorkflowRepository.snapshotMapper(http);
        assertThrows(Exception.class,()->mapper.readTree("{\"version\":1,\"version\":2}"));
    }
}
