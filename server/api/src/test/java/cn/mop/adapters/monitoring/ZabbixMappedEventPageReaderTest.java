package cn.mop.adapters.monitoring;

import static org.junit.jupiter.api.Assertions.*;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.MappedSourcePage;
import cn.mop.domain.monitoring.MonitoringObjectMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ZabbixMappedEventPageReaderTest {

    final UUID pool = UUID.randomUUID();
    final MonitoringObjectMapping.Key key = new MonitoringObjectMapping.Key(
        pool,
        MonitoringObjectMapping.ObjectType.HOST,
        "23"
    );
    final ObjectMapper json = new ObjectMapper();
    final MappedSourcePage.Window window = new MappedSourcePage.Window("40", "99", 1792800000, 1792800010, 2);

    ArrayNode rows(String... ids) {
        var rows = json.createArrayNode();
        for (String id : ids) {
            rows.add(
                json.valueToTree(
                    Map.of(
                        "eventid",
                        id,
                        "source",
                        "0",
                        "object",
                        "0",
                        "objectid",
                        "55",
                        "clock",
                        "1792800000",
                        "ns",
                        "123456789",
                        "value",
                        "1",
                        "severity",
                        "4",
                        "r_eventid",
                        "0",
                        "hosts",
                        List.of(Map.of("hostid", "23"))
                    )
                )
            );
        }
        return rows;
    }

    @Test
    void hostFilterAndLookaheadKeepExactCanonicalBytes() throws Exception {
        var page = new ZabbixMappedEventPageReader(pool, params -> {
            assertEquals(List.of("23"), params.get("hostids"));
            assertEquals(3, params.get("limit"));
            assertEquals("40", params.get("eventid_from"));
            return rows("41", "44", "90");
        }).read(key, window);
        assertEquals(2, page.events().size());
        assertEquals("45", page.nextEventId());
        var one = new ZabbixMappedEventReader(pool, query -> ZabbixEventPuller.decode(query, rows("41"))).read(
            key,
            "41"
        );
        assertArrayEquals(one.payload(), page.events().get(0).payload());
        assertEquals(123456789, page.events().get(0).occurredAt().getNano());
    }

    @Test
    void malformedOutOfOrderOrForeignRowsFailWithoutPartialPage() {
        var foreign = rows("41", "44");
        ((com.fasterxml.jackson.databind.node.ObjectNode) foreign.get(1)).set(
            "hosts",
            json.valueToTree(List.of(Map.of("hostid", "24")))
        );
        var foreignLookahead = rows("41", "44", "90");
        ((com.fasterxml.jackson.databind.node.ObjectNode) foreignLookahead.get(2)).set("hosts", json.valueToTree(List.of(Map.of("hostid", "24"))));
        for (var data : List.of(foreignLookahead, foreign, rows("44", "41"), rows("41", "41"), rows("100"))) {
            assertEquals(
                503,
                assertThrows(DomainException.class, () ->
                    new ZabbixMappedEventPageReader(pool, params -> data).read(key, window)
                ).status
            );
        }
    }

    @Test
    void emptyPageAndSourceFailureHaveExplicitDifferentOutcomes() {
        var page = new ZabbixMappedEventPageReader(pool, params -> rows()).read(key, window);
        assertTrue(page.events().isEmpty());
        assertNull(page.nextEventId());
        var failed = assertThrows(DomainException.class, () ->
            new ZabbixMappedEventPageReader(pool, params -> {
                throw new IllegalStateException("PRIVATE_SOURCE_TOKEN");
            }).read(key, window)
        );
        assertEquals(503, failed.status);
        assertFalse(failed.getMessage().contains("PRIVATE"));
    }

    @Test
    void itemMappingNeverReadsSource() {
        assertEquals(
            422,
            assertThrows(DomainException.class, () ->
                new ZabbixMappedEventPageReader(pool, params -> {
                    throw new AssertionError();
                }).read(new MonitoringObjectMapping.Key(pool, MonitoringObjectMapping.ObjectType.ITEM, "23"), window)
            ).status
        );
    }
}
