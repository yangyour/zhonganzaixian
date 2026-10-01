package cn.mop.domain.configuration;

import cn.mop.domain.DomainException;
import cn.mop.domain.configuration.ConfigurationResolver.Definition;
import cn.mop.domain.configuration.ConfigurationResolver.Resolution;
import cn.mop.domain.configuration.ConfigurationResolver.Revision;
import cn.mop.domain.configuration.ConfigurationResolver.Status;
import cn.mop.domain.configuration.ConfigurationScope.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Detached diagnostic or create-time snapshot; persistence and object creation remain caller-owned. */
public final class ConfigurationSnapshot {
    public enum Purpose { DIAGNOSTIC, CREATE_TIME }
    public enum ObjectKind { TICKET, REPORT }

    private final Purpose purpose;
    private final ObjectKind objectKind;
    private final String objectId;
    private final Context context;
    private final Map<String, Resolution> entries;

    private ConfigurationSnapshot(Purpose purpose, ObjectKind objectKind, String objectId, Context context,
                                  List<Definition> definitions, List<Revision> revisions) {
        this.purpose = purpose;
        this.objectKind = objectKind;
        this.objectId = objectId;
        this.context = Objects.requireNonNull(context, "context");
        Objects.requireNonNull(definitions, "definitions");
        if (definitions.isEmpty()) throw new IllegalArgumentException("A snapshot requires explicit configuration definitions");
        List<Revision> inputs = List.copyOf(Objects.requireNonNull(revisions, "revisions"));
        Map<String, Resolution> resolved = new LinkedHashMap<>();
        for (Definition definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            if (resolved.containsKey(definition.key())) throw new IllegalArgumentException("Duplicate configuration definition: " + definition.key());
            resolved.put(definition.key(), ConfigurationResolver.resolve(definition, context, inputs));
        }
        entries = Collections.unmodifiableMap(resolved);
    }

    public static ConfigurationSnapshot diagnostic(Context context, List<Definition> definitions, List<Revision> revisions) {
        return new ConfigurationSnapshot(Purpose.DIAGNOSTIC, null, null, context, definitions, revisions);
    }

    /** A pending required input fails before the caller persists an object or performs a side effect. */
    public static ConfigurationSnapshot forCreation(ObjectKind kind, String objectId, Context context,
                                                    List<Definition> requiredDefinitions, List<Revision> revisions) {
        Objects.requireNonNull(kind, "object kind");
        ConfigurationScope.requireText(objectId, "object identifier");
        ConfigurationSnapshot snapshot = new ConfigurationSnapshot(Purpose.CREATE_TIME, kind, objectId, context, requiredDefinitions, revisions);
        if (!snapshot.resolved()) {
            List<Map<String, Object>> details = new ArrayList<>();
            snapshot.entries.values().stream().filter(entry -> entry.status() == Status.CONFIG_PENDING).forEach(entry -> {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("configuration_key", entry.definition().key());
                detail.put("question_ids", entry.questionIds());
                detail.put("reasons", entry.reasons());
                details.add(Collections.unmodifiableMap(detail));
            });
            throw new DomainException(422, "CONFIG_PENDING", "Required configuration has not been resolved", List.copyOf(details));
        }
        return snapshot;
    }

    public Purpose purpose() { return purpose; }
    public ObjectKind objectKind() { return objectKind; }
    public String objectId() { return objectId; }
    public Context context() { return context; }
    public Map<String, Resolution> entries() { return entries; }
    public boolean resolved() { return entries.values().stream().allMatch(entry -> entry.status() == Status.RESOLVED); }
}
