package cn.mop.persistence;

import cn.mop.domain.DomainException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Reloads bounded, operator-controlled grants at every authorization boundary; never caches an allow decision. */
public final class BackupGrantFile implements Supplier<List<BackupTaskGrant>> {
    private static final int MAX_BYTES = 262144;
    private static final Set<String> FIELDS = Set.of("binding_reference", "tenant_id", "asset_id", "actor_id",
            "permission_version", "expires_at", "operations");
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** Absolute private configuration path; paths and parsing details never appear in public errors. */
    private final Path path;

    public BackupGrantFile(Path path) {
        if (path == null || !path.isAbsolute()) {
            throw unavailable();
        }
        this.path = path.normalize();
    }

    @Override
    public List<BackupTaskGrant> get() {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw notConfigured();
            }
            byte[] bytes;
            try (var input = Files.newInputStream(path)) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) {
                throw unavailable();
            }
            JsonNode root = MAPPER.readTree(bytes);
            exactFields(root, Set.of("schema_version", "grants"));
            if (!root.path("schema_version").isIntegralNumber() || !root.path("schema_version").canConvertToInt()
                    || root.path("schema_version").intValue() != 1
                    || !root.path("grants").isArray() || root.path("grants").size() > 1000) {
                throw unavailable();
            }
            var grants = new ArrayList<BackupTaskGrant>();
            var identities = new HashSet<String>();
            for (JsonNode row : root.path("grants")) {
                exactFields(row, FIELDS);
                if (!row.path("permission_version").isIntegralNumber()
                        || !row.path("permission_version").canConvertToLong()
                        || !row.path("operations").isArray()) {
                    throw unavailable();
                }
                Set<BackupTaskGrant.Operation> operations = new HashSet<>();
                for (JsonNode value : row.path("operations")) {
                    if (!value.isTextual() || !operations.add(BackupTaskGrant.Operation.valueOf(value.textValue()))) {
                        throw unavailable();
                    }
                }
                var grant = new BackupTaskGrant(text(row, "binding_reference"), uuid(row, "tenant_id"),
                        uuid(row, "asset_id"), uuid(row, "actor_id"), row.path("permission_version").longValue(),
                        Instant.parse(text(row, "expires_at")), operations);
                String identity = grant.bindingReference() + ":" + grant.tenantId() + ":" + grant.assetId() + ":" + grant.actorId();
                if (!identities.add(identity)) {
                    throw unavailable();
                }
                grants.add(grant);
            }
            return List.copyOf(grants);
        } catch (DomainException failure) {
            if ("BACKUP_ACCESS_NOT_CONFIGURED".equals(failure.code)) {
                throw notConfigured();
            }
            throw unavailable();
        } catch (IOException | RuntimeException failure) {
            throw unavailable();
        }
    }

    private static void exactFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) {
            throw unavailable();
        }
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw unavailable();
        }
    }

    private static String text(JsonNode row, String key) {
        if (!row.path(key).isTextual()) {
            throw unavailable();
        }
        return row.path(key).textValue();
    }

    private static UUID uuid(JsonNode row, String key) {
        String value = text(row, key);
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) {
            throw unavailable();
        }
        return parsed;
    }

    private static DomainException notConfigured() {
        return new DomainException(503, "BACKUP_ACCESS_NOT_CONFIGURED", "独立备份授权配置尚未提供");
    }

    private static DomainException unavailable() {
        return new DomainException(503, "BACKUP_ACCESS_UNAVAILABLE", "独立备份授权配置无法核对");
    }
}
