package cn.mop.adapters.cmdb;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetimport.CmdbImportExecutionRepository;
import cn.mop.domain.assetimport.HostFormatValidator;
import cn.mop.domain.assetimport.source.ParsedArtifact;
import cn.mop.domain.assetmetadata.ManualAssetMetadata;
import cn.mop.domain.assetmetadata.ManualWarrantyDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Explicit tenant configuration maps reviewed source columns; it never guesses references or device types. */
public final class CmdbImportPlanner implements CmdbImportExecutionRepository.Planner {
    private static final Set<String> FIELDS = Set.of("schema_version", "type_mapping", "site_mapping",
            "owner_mapping", "default_site_id", "address_policy", "preserve_source_columns",
            "date_policy", "business_description_mode");
    private static final Set<Integer> EXTENSIONS = Set.of(15, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32);
    private final ObjectMapper mapper;
    private final CustomerCmdbDictionary dictionary;

    public CmdbImportPlanner(ObjectMapper mapper) {
        this.mapper = mapper;
        this.dictionary = CustomerCmdbDictionary.load(mapper);
    }

    @Override
    public CmdbImportExecutionRepository.Plan prepare(ParsedArtifact artifact, Map<String, Object> configuration) {
        JsonNode policy = mapper.valueToTree(configuration);
        validateConfiguration(policy);
        HostFormatValidator.Policy addresses = addressPolicy(policy.path("address_policy"));
        CmdbSourcePreflight.Result preflight;
        try {
            preflight = new CmdbSourcePreflight(dictionary).check(artifact);
        } catch (IllegalArgumentException invalid) {
            throw new DomainException(422, "CMDB_SOURCE_SCHEMA_MISMATCH", "来源必须为已解析的客户33列模板");
        }
        if (preflight.errorRows() > 0) {
            throw new DomainException(422, "CMDB_SOURCE_INVALID", "来源字典或单元格校验失败，请先修正原文件");
        }
        if (artifact.rows().isEmpty() || artifact.rows().size() > 500) {
            throw new DomainException(422, "CMDB_BATCH_SIZE_INVALID", "导入需要1至500条来源记录");
        }
        Set<Integer> preserved = new HashSet<>();
        policy.path("preserve_source_columns").forEach(n -> preserved.add(n.intValue()));
        List<CmdbImportExecutionRepository.Row> rows = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (var source : artifact.rows()) {
            String[] values = new String[33];
            for (var cell : source.cells()) {
                values[cell.columnIndex()] = cell.lexical() == null || cell.lexical().isEmpty() ? null : cell.lexical();
                if (Set.of(10, 11, 12, 17).contains(cell.columnIndex())
                        && values[cell.columnIndex()] != null && cell.kind() != ParsedArtifact.CellKind.TEXT) {
                    throw rowError(source, "CMDB_DATE_POLICY_UNSUPPORTED");
                }
            }
            for (int column : EXTENSIONS) {
                if (values[column] != null && !preserved.contains(column)) {
                    throw rowError(source, "CMDB_EXTENSION_DISPOSITION_REQUIRED");
                }
            }
            String subtype = dictionary.subtypes().stream()
                    .filter(s -> s.code().equals(values[16]) || s.label().equals(values[16]))
                    .map(CustomerCmdbDictionary.Subtype::code).findFirst().orElse(null);
            String type = lookup(policy.path("type_mapping"), subtype);
            String site = values[6] == null ? nullable(policy.get("default_site_id"))
                    : lookup(policy.path("site_mapping"), values[6]);
            String owner = values[8] == null ? null : lookup(policy.path("owner_mapping"), values[8]);
            if (type == null || site == null || values[8] != null && owner == null) {
                throw rowError(source, "CMDB_REFERENCE_MAPPING_REQUIRED");
            }
            var address = HostFormatValidator.check(values[2], addresses);
            if (!address.valid() || address.canonical().length() > 45) {
                throw rowError(source, "CMDB_ADDRESS_INVALID");
            }
            if (!names.add(values[0])) {
                throw rowError(source, "CMDB_NAME_DUPLICATE");
            }
            try {
                var metadata = new ManualAssetMetadata(values[0], type, site, values[3], values[4], values[5],
                        values[7], owner, values[9], ManualAssetMetadata.parseDate(values[10]),
                        ManualAssetMetadata.parseDate(values[11]), ManualAssetMetadata.parseDate(values[12]), values[13], values[14]);
                var warranty = new ManualWarrantyDetails(ManualAssetMetadata.parseDate(values[17]), values[18]);
                warranty.validateAgainstExpiry(metadata.warrantyExpiry());
                rows.add(new CmdbImportExecutionRepository.Row(source.logicalRecordNumber(), source.sourceRowNumber(),
                        address.canonical(), metadata, warranty));
            } catch (DomainException invalid) {
                throw rowError(source, "CMDB_ROW_INVALID");
            }
        }
        return new CmdbImportExecutionRepository.Plan(rows, addresses);
    }

    /** Publication validation and execution use the same bounded schema; null is never an enabled policy. */
    public static void validateConfiguration(JsonNode policy) {
        Set<String> fields = new HashSet<>();
        if (policy != null && policy.isObject()) {
            policy.fieldNames().forEachRemaining(fields::add);
        }
        if (!fields.equals(FIELDS) || !policy.path("schema_version").isIntegralNumber()
                || !policy.path("schema_version").canConvertToInt() || policy.path("schema_version").intValue() != 1
                || !"ISO_DATE_ONLY".equals(policy.path("date_policy").asText())
                || !"SOURCE_TEXT".equals(policy.path("business_description_mode").asText())) {
            throw configError();
        }
        for (String key : List.of("type_mapping", "site_mapping", "owner_mapping")) {
            JsonNode entries = policy.path(key);
            if (!entries.isObject() || entries.size() > 1000) {
                throw configError();
            }
            entries.fields().forEachRemaining(e -> {
                if (e.getKey().isBlank() || e.getKey().length() > 200 || !e.getValue().isTextual()
                        || e.getValue().asText().isBlank() || e.getValue().asText().length() > 100) {
                    throw configError();
                }
                String value = e.getValue().asText();
                if (key.equals("type_mapping") && !Set.of("router", "switch", "firewall", "ap", "wireless_controller", "other").contains(value)
                        || key.equals("site_mapping") && value.length() > 64
                        || key.equals("owner_mapping") && !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                    throw configError();
                }
            });
        }
        JsonNode site = policy.get("default_site_id");
        if (!site.isNull() && (!site.isTextual() || site.asText().isBlank() || site.asText().length() > 64)) {
            throw configError();
        }
        JsonNode columns = policy.get("preserve_source_columns");
        Set<Integer> seen = new HashSet<>();
        if (!columns.isArray() || columns.size() > EXTENSIONS.size()) {
            throw configError();
        }
        for (var column : columns) {
            if (!column.isIntegralNumber() || !column.canConvertToInt() || !EXTENSIONS.contains(column.intValue())
                    || !seen.add(column.intValue())) {
                throw configError();
            }
        }
        addressPolicy(policy.get("address_policy"));
    }

    private static HostFormatValidator.Policy addressPolicy(JsonNode p) {
        Set<String> keys = new HashSet<>();
        if (p != null && p.isObject()) {
            p.fieldNames().forEachRemaining(keys::add);
        }
        if (!keys.equals(Set.of("domain_mode", "allow_single_label", "root_dot_mode", "allow_bracketed_ipv6",
                "leading_zero_mode", "mapped_ipv4_mode", "whitespace_mode", "numeric_domain_mode"))
                || !p.path("allow_single_label").isBoolean() || !p.path("allow_bracketed_ipv6").isBoolean()) {
            throw configError();
        }
        try {
            return new HostFormatValidator.Policy(true,
                    HostFormatValidator.DomainMode.valueOf(p.path("domain_mode").asText()), p.path("allow_single_label").booleanValue(),
                    HostFormatValidator.RootDotMode.valueOf(p.path("root_dot_mode").asText()), p.path("allow_bracketed_ipv6").booleanValue(),
                    HostFormatValidator.LeadingZeroMode.valueOf(p.path("leading_zero_mode").asText()),
                    HostFormatValidator.MappedIpv4Mode.valueOf(p.path("mapped_ipv4_mode").asText()),
                    HostFormatValidator.WhitespaceMode.valueOf(p.path("whitespace_mode").asText()),
                    HostFormatValidator.NumericDomainMode.valueOf(p.path("numeric_domain_mode").asText()));
        } catch (IllegalArgumentException invalid) {
            throw configError();
        }
    }

    private static String lookup(JsonNode map, String key) {
        return key == null ? null : nullable(map.get(key));
    }

    private static String nullable(JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static DomainException configError() {
        return new DomainException(422, "CMDB_IMPORT_POLICY_INVALID", "导入配置缺少明确映射、来源列处置或地址日期规则");
    }

    private static DomainException rowError(ParsedArtifact.SourceRow row, String code) {
        return new DomainException(422, code, "导入记录未通过校验，整批未写入",
                List.of(Map.of("record", row.logicalRecordNumber(), "source_row", row.sourceRowNumber())));
    }
}
