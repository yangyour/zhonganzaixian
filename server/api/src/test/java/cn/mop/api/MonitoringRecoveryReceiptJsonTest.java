package cn.mop.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Reproduces the persistence JSON round-trip that the preserved failed assertion compared. No PG claim. */
class MonitoringRecoveryReceiptJsonTest {
    @Test void originalLongAndPersistedIntegerHaveExactlyTheSameWireJsonButDifferentNodeTypes() throws Exception {
        var mapper=new ObjectMapper();
        var original=Map.of("permission_version",7L,"event_version",2L,"matching_samples",2,
            "applied",true,"confirmed_at","2026-10-23T12:00:00.123Z","span_seconds","60.000000001");
        var replay=mapper.readValue(mapper.writeValueAsBytes(original),Map.class);
        assertInstanceOf(Long.class,original.get("event_version"));
        assertInstanceOf(Integer.class,replay.get("event_version"));
        assertNotEquals(mapper.valueToTree(original),mapper.valueToTree(replay));
        assertEquals(mapper.readTree(mapper.writeValueAsBytes(original)),mapper.readTree(mapper.writeValueAsBytes(replay)));
        var different=new LinkedHashMap<>(replay);different.put("event_version",3);
        assertNotEquals(mapper.readTree(mapper.writeValueAsBytes(original)),mapper.readTree(mapper.writeValueAsBytes(different)));
    }
    @Test void exactLargeVersionsAndNanosecondDecimalStringsAreNotCoercedOrRounded() throws Exception {
        var mapper=new ObjectMapper();
        var original=Map.of("event_version",9007199254740990L,"span_seconds","0.000000001");
        var replay=mapper.readValue(mapper.writeValueAsBytes(original),Map.class);
        assertEquals(9007199254740990L,replay.get("event_version"));
        assertEquals("0.000000001",replay.get("span_seconds"));
        assertEquals(mapper.readTree(mapper.writeValueAsBytes(original)),mapper.readTree(mapper.writeValueAsBytes(replay)));
    }
}
