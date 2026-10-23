package cn.mop.adapters.cmdb;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetimport.source.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure source validation only. No database, device or customer acceptance is asserted here. */
public class CmdbImportPlannerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ObjectNode policy() throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"schema_version":1,"type_mapping":{"sw_access":"switch"},"site_mapping":{"lab":"unit-site"},
                 "owner_mapping":{},"default_site_id":null,"date_policy":"ISO_DATE_ONLY",
                 "business_description_mode":"SOURCE_TEXT","preserve_source_columns":[],
                 "address_policy":{"domain_mode":"ASCII_ONLY","allow_single_label":false,"root_dot_mode":"REJECT",
                 "allow_bracketed_ipv6":false,"leading_zero_mode":"REJECT","mapped_ipv4_mode":"CANONICAL_IPV4",
                 "whitespace_mode":"REJECT","numeric_domain_mode":"REJECT_LEGACY_ADDRESS_SHAPES"}}
                """);
    }

    public static ParsedArtifact artifact(Map<Integer, String> overrides, int count) {
        var dictionary = CustomerCmdbDictionary.load(MAPPER);
        var headers = new ArrayList<String>();
        dictionary.document().path("import_columns").forEach(n -> headers.add(n.asText()));
        var rows = new ArrayList<ParsedArtifact.SourceRow>();
        for (int i = 1; i <= count; i++) {
            var values = new HashMap<>(Map.of(0, "SYNTHETIC-import-" + i, 1, "SW", 2, "192.0.2." + i,
                    6, "lab", 14, "UNIT ONLY", 16, "sw_access"));
            values.putAll(overrides);
            var cells = new ArrayList<ParsedArtifact.SourceCell>();
            values.forEach((column, value) -> cells.add(new ParsedArtifact.SourceCell(column,
                    ParsedArtifact.CellKind.TEXT, value, value, null, null)));
            cells.sort(Comparator.comparingInt(ParsedArtifact.SourceCell::columnIndex));
            rows.add(new ParsedArtifact.SourceRow(i, i + 1, (long) i + 1, (long) i + 1, cells));
        }
        return new ParsedArtifact(ParsedArtifact.State.PARSED, ImportFormat.CSV,
                List.of(new ParsedArtifact.SheetInfo(0, "CSV", false)), 0, 1, ParsedArtifact.DateEpoch.NOT_APPLICABLE,
                ParsedArtifact.columns(headers), rows, "unit-test", ImportLimits.RESOURCE_POLICY_VERSION);
    }

    private cn.mop.domain.assetimport.CmdbImportExecutionRepository.Plan prepare(Map<Integer, String> row, ObjectNode policy, int count) {
        return new CmdbImportPlanner(MAPPER).prepare(artifact(row, count), MAPPER.convertValue(policy, Map.class));
    }

    @Test void createsBoundDraftRowsAndPreservesExplicitMetadata() throws Exception {
        var plan = prepare(Map.of(3, "source-brand", 10, "2025-01-01", 17, "2025-01-02", 18, "source-supplier"), policy(), 2);
        assertEquals(2, plan.rows().size());
        assertEquals("unit-site", plan.rows().get(0).metadata().siteId());
        assertEquals("source-brand", plan.rows().get(0).metadata().brand());
        assertEquals("source-supplier", plan.rows().get(0).warranty().warrantySupplier());
        assertEquals(2, plan.rows().get(0).sourceRow());
        assertTrue(plan.addressPolicy().complete());
    }

    @Test void absentPolicyAndUnknownFieldsNeverGainDefaults() throws Exception {
        assertThrows(DomainException.class, () -> new CmdbImportPlanner(MAPPER).prepare(artifact(Map.of(), 1), Map.of()));
        var p = policy();
        p.put("enabled", true);
        assertThrows(DomainException.class, () -> prepare(Map.of(), p, 1));
    }

    @Test void typeAndOwnerReferencesRequireExplicitMappings() throws Exception {
        assertEquals("CMDB_REFERENCE_MAPPING_REQUIRED", assertThrows(DomainException.class,
                () -> prepare(Map.of(8, "someone"), policy(), 1)).code);
        assertThrows(DomainException.class, () -> prepare(Map.of(16, "sw_core"), policy(), 1));
        assertThrows(DomainException.class, () -> prepare(Map.of(6, "foreign-site"), policy(), 1));
    }

    @Test void blankSiteOnlyUsesAnExplicitConfiguredReference() throws Exception {
        assertThrows(DomainException.class, () -> prepare(Map.of(6, ""), policy(), 1));
        var p = policy();
        p.put("default_site_id", "explicit-site");
        assertEquals("explicit-site", prepare(Map.of(6, ""), p, 1).rows().get(0).metadata().siteId());
    }

    @Test void extendedValuesRequireExplicitSourcePreservation() throws Exception {
        assertEquals("CMDB_EXTENSION_DISPOSITION_REQUIRED", assertThrows(DomainException.class,
                () -> prepare(Map.of(20, "rack-A"), policy(), 1)).code);
        var p = policy();
        p.withArray("preserve_source_columns").add(20);
        assertEquals(1, prepare(Map.of(20, "rack-A"), p, 1).rows().size());
        p.withArray("preserve_source_columns").add(0);
        assertThrows(DomainException.class, () -> prepare(Map.of(), p, 1));
    }

    @Test void rejectsDuplicateNamesBeforeAnyRepositoryWrite() throws Exception {
        assertEquals("CMDB_NAME_DUPLICATE", assertThrows(DomainException.class,
                () -> prepare(Map.of(0, "same"), policy(), 2)).code);
    }

    @Test void addressPolicyRejectsDecorationsAndCanonicalizesWithoutDns() throws Exception {
        for (String value : List.of("http://example.test/path", "192.0.2.01", "host:123", "2001:db8::1%eth0")) {
            assertThrows(DomainException.class, () -> prepare(Map.of(2, value), policy(), 1));
        }
        assertEquals("192.0.2.1", prepare(Map.of(2, "::ffff:192.0.2.1"), policy(), 1).rows().get(0).address());
    }

    @Test void rejectsInvalidDatesEmptyNotesAndMismatchedTypes() throws Exception {
        for (var value : List.of(Map.of(10, "2025/01/02"), Map.of(14, ""), Map.of(1, "RT"), Map.of(17, "2025-03-01", 11, "2025-01-01"))) {
            assertThrows(DomainException.class, () -> prepare(value, policy(), 1));
        }
    }

    @Test void forbidsEmptyAndOversizedBatches() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> artifact(Map.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> artifact(Map.of(), 501));
    }

    @Test void integerOverflowCannotImpersonateSchemaVersionOne() throws Exception {
        var p = policy();
        p.put("schema_version", 4294967297L);
        assertThrows(DomainException.class, () -> prepare(Map.of(), p, 1));
    }
}
