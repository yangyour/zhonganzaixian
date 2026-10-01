package cn.mop.domain.assetimport;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * M3-6 pure precheck over already parsed cells. No file parser, I/O, logging, writes, quota,
 * operation, audit, preview hash or import execution is implemented here.
 *
 * <p>The caller supplies a server-authorized tenant and trustworthy, tenant-bound facts. Complete
 * means the authoritative query actually finished, not that an empty collection was constructed.
 * Q20 dictionaries and Q31 conflict decisions must not be invented from the existing six-type
 * endpoint. PASS/WARNING only describe this calculation: a future importer must reauthorize and
 * requery facts inside its protected write transaction, enforce quota/idempotency and audit.
 * These synthetic-input unit checks do not prove integration or the 500-row/60s full-import SLA.
 */
public final class AssetImportPrechecker {
    public static final int MAX_ROWS = 500;
    private AssetImportPrechecker() {}

    /** Exactly the contract's fifteen columns, in template order; no credential columns. */
    public enum Column {
        NAME("设备名称"), TYPE("设备类型"), ADDRESS("IP或域名"), BRAND("品牌"), MODEL("型号"),
        SERIAL_NUMBER("序列号"), SITE("站点"), LOCATION("位置"), OWNER("负责人"), BUSINESS("所属业务"),
        PURCHASE_DATE("采购日期"), WARRANTY_EXPIRY("维保到期日"), OVERDUE_SERVICE_DATE("超期服役日期"),
        COLLECTION_METHOD("采集方式"), NOTE("备注");
        private final String label;
        Column(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum Status { PASS, WARNING, ERROR, CONFIG_PENDING, REFERENCE_BLOCKED, INPUT_BLOCKED }
    public enum Level { ERROR, WARNING, CONFIG_PENDING, REFERENCE_BLOCKED, INPUT_BLOCKED }
    public enum Code {
        REQUIRED_MISSING(Level.ERROR), ADDRESS_INVALID(Level.ERROR), NAME_DUPLICATE(Level.ERROR),
        IP_CONFLICT(Level.ERROR), TYPE_NOT_ALLOWED(Level.ERROR),
        DATE_INVALID(Level.WARNING), OWNER_NOT_MEMBER(Level.WARNING), BRAND_NO_OID(Level.WARNING),
        FACTS_PENDING(Level.CONFIG_PENDING), RULES_PENDING(Level.CONFIG_PENDING),
        DATE_AMBIGUOUS(Level.CONFIG_PENDING), DICTIONARY_AMBIGUOUS(Level.CONFIG_PENDING),
        TENANT_REFERENCE_UNSAFE(Level.REFERENCE_BLOCKED), SITE_REFERENCE_UNRESOLVED(Level.REFERENCE_BLOCKED),
        TOO_MANY_ROWS(Level.INPUT_BLOCKED), DUPLICATE_ROW_NUMBER(Level.INPUT_BLOCKED);
        private final Level level;
        Code(Level level) { this.level = level; }
        public Level level() { return level; }
    }

    /** Choice of equality is input policy: this class never assumes case folding or NFKC. */
    public enum TextRule {
        EXACT, STRIP, STRIP_LOWERCASE;
        String apply(String value) {
            if (value == null || value.isBlank()) return null;
            return switch (this) {
                case EXACT -> value;
                case STRIP -> value.strip();
                case STRIP_LOWERCASE -> value.strip().toLowerCase(Locale.ROOT);
            };
        }
    }

    public enum DateAmbiguity { FIRST_PATTERN, REQUIRE_UNIQUE_DATE }
    public enum IpScope { TENANT, SITE, PLATFORM }

    /** This is evidence supplied by the caller, not an authentication mechanism. */
    public record AuthorizedTenant(String tenantId, String authorizationReference) {
        public AuthorizedTenant {
            identifier(tenantId, "tenantId"); identifier(authorizationReference, "authorizationReference");
        }
    }

    public record TenantReference(String tenantId, String id) {
        public TenantReference { identifier(tenantId, "tenantId"); identifier(id, "id"); }
    }

    /** Alias keys are interpreted with Rules.dictionaryKey; duplicate aliases remain ambiguous. */
    public record Dictionary<T>(String tenantId, boolean complete, Map<String, List<T>> entries) {
        public Dictionary {
            identifier(tenantId, "tenantId"); Objects.requireNonNull(entries, "entries");
            Map<String, List<T>> detached = new LinkedHashMap<>();
            entries.forEach((key, values) -> {
                identifier(key, "dictionaryKey");
                detached.put(key, List.copyOf(Objects.requireNonNull(values, "dictionaryValues")));
            });
            entries = Collections.unmodifiableMap(detached);
        }
    }

    /** NOTE is always required. Other required fields are not specified exhaustively by M3-6. */
    public record RequiredFields(boolean complete, Set<Column> fields) {
        public RequiredFields { fields = Set.copyOf(Objects.requireNonNull(fields, "fields")); }
    }

    /** Patterns use strict JDK LocalDate parsing, e.g. uuuu-MM-dd, never Excel serials or epoch fallback. */
    public record DateRules(boolean complete, List<String> patterns, DateAmbiguity ambiguity) {
        public DateRules {
            patterns = List.copyOf(Objects.requireNonNull(patterns, "patterns"));
            Objects.requireNonNull(ambiguity, "ambiguity");
            if (complete && patterns.isEmpty()) throw new IllegalArgumentException("Complete date rules need patterns");
            for (String pattern : patterns) DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
        }
    }

    /** Missing rules may be null and produce CONFIG_PENDING; no customer defaults are substituted. */
    public record Rules(RequiredFields requiredFields, TextRule nameKey, TextRule dictionaryKey,
                        TextRule otherText, Boolean allowMissingSite, HostFormatValidator.Policy addressPolicy,
                        DateRules dateRules) {}

    /** A complete tenant-scoped database-name query; names are normalized with the same nameKey rule. */
    public record NameFacts(String tenantId, boolean complete, Set<String> existingNames) {
        public NameFacts {
            identifier(tenantId, "tenantId");
            existingNames = Set.copyOf(Objects.requireNonNull(existingNames, "existingNames"));
            for (String name : existingNames) identifier(name, "existingName");
        }
    }

    /** SITE facts must identify their site; addresses must be bare numeric literals, never resolved domains. */
    public record ExistingIp(String address, String siteId) {
        public ExistingIp { identifier(address, "address"); if (siteId != null) identifier(siteId, "siteId"); }
    }

    /**
     * queryTenantId binds the authorized query, including a confirmed PLATFORM conflict query.
     * complete=false means UNKNOWN rather than NO_MATCH. scope=null or missing decision evidence
     * keeps Q31 pending. This does not establish or approve any real Q31 policy.
     */
    public record IpFacts(String queryTenantId, boolean complete, IpScope scope, String scopeDecisionReference,
                          List<ExistingIp> existingIps) {
        public IpFacts {
            identifier(queryTenantId, "queryTenantId");
            if (scopeDecisionReference != null) identifier(scopeDecisionReference, "scopeDecisionReference");
            existingIps = List.copyOf(Objects.requireNonNull(existingIps, "existingIps"));
        }
    }

    public record Context(AuthorizedTenant authorizedTenant, Dictionary<String> types,
                          Dictionary<TenantReference> sites, Dictionary<TenantReference> members,
                          Dictionary<Boolean> brandsWithOid, Rules rules, NameFacts names, IpFacts ips) {
        public Context { Objects.requireNonNull(authorizedTenant, "authorizedTenant"); }
    }

    /** Header/blank-row handling belongs to the future file adapter. Row numbers are original positive source numbers. */
    public record ParsedRow(int rowNumber, Map<Column, String> cells) {
        public ParsedRow {
            if (rowNumber < 1) throw new IllegalArgumentException("rowNumber must be positive");
            cells = AssetImportPrechecker.cells(cells);
        }
    }

    /** Non-contract blockers have distinct levels and explicit safety/configuration references. */
    public record Finding(Code code, Column column, String original, Object normalized, Integer relatedRow,
                          String reason, String suggestion, List<String> references) {
        public Finding {
            Objects.requireNonNull(code, "code"); Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(suggestion, "suggestion"); references = List.copyOf(references);
            normalizedValue(normalized);
        }
        public Level level() { return code.level(); }
    }

    /** Normalized dates are LocalDate or null; text/reference/address cells are String or null. */
    public record RowResult(int rowNumber, Status status, Map<Column, String> original,
                            Map<Column, Object> normalized, List<Finding> findings) {
        public RowResult {
            original = cells(original); normalized = normalizedCells(normalized); findings = List.copyOf(findings);
        }
        public Map<Column, String> normalizationSuggestions() {
            EnumMap<Column, String> suggestions = new EnumMap<>(Column.class);
            for (Column column : Column.values()) {
                Object value = normalized.get(column);
                suggestions.put(column, value != null && !Objects.equals(original.get(column), value.toString())
                        ? "采用已核验的规范值" : null);
            }
            return Collections.unmodifiableMap(suggestions);
        }
    }

    public record Result(Status status, List<RowResult> rows, List<Finding> batchFindings) {
        public Result { rows = List.copyOf(rows); batchFindings = List.copyOf(batchFindings); }
    }

    public static Result check(Context context, List<ParsedRow> input) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(input, "rows");
        List<Finding> batch = new ArrayList<>();
        if (input.size() > MAX_ROWS) return new Result(Status.INPUT_BLOCKED, List.of(), List.of(global(
                Code.TOO_MANY_ROWS, "单批数据行超过合同500行上限", "拆分为每批最多500行", "M3-6")));
        List<ParsedRow> rows = List.copyOf(input);
        Set<Integer> numbers = new LinkedHashSet<>();
        for (ParsedRow row : rows) if (!numbers.add(row.rowNumber())) {
            batch.add(global(Code.DUPLICATE_ROW_NUMBER, "原始行号重复，无法可靠定位回执", "提供唯一的原始文件行号", "M3-6")); break;
        }
        if (!batch.isEmpty()) return new Result(Status.INPUT_BLOCKED, List.of(), batch);

        checkContext(context, batch);
        if (batch.stream().anyMatch(f -> f.level() == Level.REFERENCE_BLOCKED)) {
            List<RowResult> blocked = rows.stream().map(row -> new RowResult(row.rowNumber(), Status.REFERENCE_BLOCKED,
                    row.cells(), emptyNormalized(), batch)).toList();
            return new Result(Status.REFERENCE_BLOCKED, blocked, batch);
        }
        Rules rules = context.rules();
        Map<String, Integer> seenNames = new HashMap<>();
        Set<String> databaseNames = new LinkedHashSet<>();
        if (context.names() != null && rules != null && rules.nameKey() != null) {
            for (String name : context.names().existingNames()) databaseNames.add(rules.nameKey().apply(name));
        }
        Map<String, List<String>> typeIndex = index(context.types(), rules);
        Map<String, List<TenantReference>> siteIndex = index(context.sites(), rules);
        Map<String, List<TenantReference>> memberIndex = index(context.members(), rules);
        Map<String, List<Boolean>> brandIndex = index(context.brandsWithOid(), rules);
        Set<IpKey> ipKeys = ipKeys(context, batch);
        List<RowResult> results = new ArrayList<>();
        for (ParsedRow row : rows) {
            Map<Column, Object> normalized = emptyNormalized();
            List<Finding> findings = new ArrayList<>(batch);
            for (Column column : Column.values()) normalized.put(column,
                    rules != null && rules.otherText() != null ? rules.otherText().apply(row.cells().get(column)) : null);
            required(row, rules, findings);
            String name = rules != null && rules.nameKey() != null ? rules.nameKey().apply(row.cells().get(Column.NAME)) : null;
            normalized.put(Column.NAME, name);
            if (name != null) {
                Integer first = seenNames.putIfAbsent(name, row.rowNumber());
                if (first != null) findings.add(rowFinding(Code.NAME_DUPLICATE, Column.NAME, row, name, first,
                        "与第" + first + "行重复", "修改设备名称使其在租户内唯一", "M3-6"));
                if (databaseNames.contains(name)) findings.add(rowFinding(Code.NAME_DUPLICATE, Column.NAME, row, name, null,
                        "与租户内已有设备名称重复", "确认已有设备并使用租户内唯一名称", "M3-6"));
            }
            type(row, context, typeIndex, normalized, findings);
            String site = site(row, context, siteIndex, normalized, findings);
            owner(row, context, memberIndex, normalized, findings);
            brand(row, context, brandIndex, findings);
            dates(row, rules, normalized, findings);
            address(row, context, site, ipKeys, normalized, findings);
            results.add(new RowResult(row.rowNumber(), status(findings), row.cells(), normalized, findings));
        }
        List<Finding> all = new ArrayList<>(batch);
        results.forEach(row -> all.addAll(row.findings()));
        return new Result(status(all), results, batch);
    }

    private static void checkContext(Context context, List<Finding> findings) {
        String tenant = context.authorizedTenant().tenantId();
        List<Dictionary<?>> dictionaries = new ArrayList<>();
        dictionaries.add(context.types()); dictionaries.add(context.sites());
        dictionaries.add(context.members()); dictionaries.add(context.brandsWithOid());
        for (Dictionary<?> dictionary : dictionaries) {
            if (dictionary != null && !tenant.equals(dictionary.tenantId())) findings.add(unsafe());
            if (dictionary == null || !dictionary.complete()) findings.add(global(Code.FACTS_PENDING,
                    "类型、站点、成员或品牌词典未完整核验", "提供已授权租户的完整词典查询事实", "Q20", "Q31"));
            if (dictionary != null) for (List<?> entries : dictionary.entries().values()) for (Object entry : entries) {
                if (entry instanceof TenantReference reference && !tenant.equals(reference.tenantId())) findings.add(unsafe());
                if (entry instanceof String text && text.isBlank()) findings.add(global(Code.FACTS_PENDING,
                        "类型词典存在未确定的规范值", "补齐权威设备类型词典", "Q20"));
            }
        }
        if (context.names() != null && !tenant.equals(context.names().tenantId())
                || context.ips() != null && !tenant.equals(context.ips().queryTenantId())) findings.add(unsafe());
        if (context.names() == null || !context.names().complete()) findings.add(global(Code.FACTS_PENDING,
                "租户内名称冲突事实为UNKNOWN", "完成租户内已有设备名称查询", "M3-6"));
        IpFacts ips = context.ips();
        if (ips == null || !ips.complete() || ips.scope() == null || ips.scopeDecisionReference() == null) {
            findings.add(global(Code.FACTS_PENDING, "IP冲突域或库内冲突事实为UNKNOWN", "按已确认Q31冲突域提供完整事实", "Q31"));
        }
        Rules rules = context.rules();
        if (rules == null || rules.requiredFields() == null || !rules.requiredFields().complete()
                || rules.nameKey() == null || rules.dictionaryKey() == null || rules.otherText() == null
                || rules.allowMissingSite() == null || rules.addressPolicy() == null || !rules.addressPolicy().complete()
                || rules.dateRules() == null || !rules.dateRules().complete()) {
            findings.add(global(Code.RULES_PENDING, "必填、日期、地址或文本规范化规则未完整确认", "提供显式输入规则；备注始终必填", "Q20", "Q31", "M3-6"));
        }
    }

    private static void required(ParsedRow row, Rules rules, List<Finding> findings) {
        Set<Column> required = new LinkedHashSet<>(); required.add(Column.NOTE);
        if (rules != null && rules.requiredFields() != null) required.addAll(rules.requiredFields().fields());
        for (Column column : required) if (blank(row.cells().get(column))) findings.add(rowFinding(Code.REQUIRED_MISSING,
                column, row, null, null, "必填字段缺失", "补充该必填字段", "M3-6"));
    }

    private static void type(ParsedRow row, Context context, Map<String, List<String>> index,
                             Map<Column, Object> normalized, List<Finding> findings) {
        normalized.put(Column.TYPE, null);
        String key = dictionaryKey(row, Column.TYPE, context.rules());
        if (key == null) return;
        List<String> matches = index.getOrDefault(key, List.of());
        if (matches.size() == 1 && !matches.get(0).isBlank()) normalized.put(Column.TYPE, matches.get(0));
        else if (matches.isEmpty() && complete(context.types())) findings.add(rowFinding(Code.TYPE_NOT_ALLOWED,
                Column.TYPE, row, null, null, "设备类型不在已提供枚举范围内", "使用已确认词典中的设备类型", "M3-6", "Q20"));
        else if (!matches.isEmpty()) findings.add(ambiguous(row, Column.TYPE));
    }

    private static String site(ParsedRow row, Context context, Map<String, List<TenantReference>> index,
                                Map<Column, Object> normalized, List<Finding> findings) {
        normalized.put(Column.SITE, null);
        String key = dictionaryKey(row, Column.SITE, context.rules());
        if (key == null && blank(row.cells().get(Column.SITE)) && context.rules() != null
                && Boolean.TRUE.equals(context.rules().allowMissingSite())) return null;
        List<TenantReference> matches = key == null ? List.of() : index.getOrDefault(key, List.of());
        if (matches.size() == 1 && matches.get(0).tenantId().equals(context.authorizedTenant().tenantId())) {
            normalized.put(Column.SITE, matches.get(0).id()); return matches.get(0).id();
        }
        findings.add(rowFinding(Code.SITE_REFERENCE_UNRESOLVED, Column.SITE, row, null, null,
                "站点无法唯一解析为已授权租户对象", "先核验站点归属和唯一引用，不能按名称猜测", "AGENTS.md:租户授权", "03:租户联合定位"));
        return null;
    }

    private static void owner(ParsedRow row, Context context, Map<String, List<TenantReference>> index,
                              Map<Column, Object> normalized, List<Finding> findings) {
        normalized.put(Column.OWNER, null);
        String key = dictionaryKey(row, Column.OWNER, context.rules());
        if (key == null) return;
        List<TenantReference> matches = index.getOrDefault(key, List.of());
        if (matches.size() == 1) normalized.put(Column.OWNER, matches.get(0).id());
        else if (matches.isEmpty() && complete(context.members())) findings.add(rowFinding(Code.OWNER_NOT_MEMBER,
                Column.OWNER, row, null, null, "负责人非本租户成员，规范值已置空", "选择已核验的本租户成员或保留空值", "M3-6"));
        else if (!matches.isEmpty()) findings.add(ambiguous(row, Column.OWNER));
    }

    private static void brand(ParsedRow row, Context context, Map<String, List<Boolean>> index, List<Finding> findings) {
        String key = dictionaryKey(row, Column.BRAND, context.rules());
        if (key == null) return;
        List<Boolean> matches = index.getOrDefault(key, List.of());
        if (matches.size() > 1) findings.add(ambiguous(row, Column.BRAND));
        else if (matches.size() == 1 && !matches.get(0) || matches.isEmpty() && complete(context.brandsWithOid())) {
            findings.add(rowFinding(Code.BRAND_NO_OID, Column.BRAND, row, key, null,
                    "品牌无OID映射，仅提供可用性监控", "补充并核验品牌/型号/固件OID映射", "M3-6", "Q20"));
        }
    }

    private static void dates(ParsedRow row, Rules rules, Map<Column, Object> normalized, List<Finding> findings) {
        for (Column column : List.of(Column.PURCHASE_DATE, Column.WARRANTY_EXPIRY, Column.OVERDUE_SERVICE_DATE)) {
            normalized.put(column, null);
            String raw = row.cells().get(column);
            if (blank(raw) || rules == null || rules.dateRules() == null || !rules.dateRules().complete()) continue;
            String value = rules.otherText() == null ? raw : rules.otherText().apply(raw);
            Set<LocalDate> parsed = new LinkedHashSet<>();
            for (String pattern : rules.dateRules().patterns()) {
                try {
                    parsed.add(LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)));
                    if (rules.dateRules().ambiguity() == DateAmbiguity.FIRST_PATTERN) break;
                } catch (DateTimeParseException exception) { /* Invalid dates stay null; input is never logged. */ }
            }
            if (parsed.size() == 1) normalized.put(column, parsed.iterator().next());
            else if (parsed.isEmpty()) findings.add(rowFinding(Code.DATE_INVALID, column, row, null, null,
                    "日期格式非法，规范值已置空", "使用已提供日期规则中的有效日期，或保留空值", "M3-6"));
            else findings.add(rowFinding(Code.DATE_AMBIGUOUS, column, row, null, null,
                    "日期匹配到多个不同日期，解析规则待明确", "提供无歧义日期或明确格式优先级", "Q31"));
        }
    }

    private record IpKey(String canonical, String siteId) {}

    private static Set<IpKey> ipKeys(Context context, List<Finding> batch) {
        Set<IpKey> result = new LinkedHashSet<>();
        if (context.ips() == null || context.ips().scope() == null || context.ips().scopeDecisionReference() == null
                || context.rules() == null || context.rules().addressPolicy() == null) return result;
        for (ExistingIp fact : context.ips().existingIps()) {
            HostFormatValidator.Check checked = HostFormatValidator.check(fact.address(), context.rules().addressPolicy());
            if (!checked.valid() || checked.kind() == HostFormatValidator.Kind.DOMAIN
                    || context.ips().scope() == IpScope.SITE && fact.siteId() == null) {
                batch.add(global(Code.FACTS_PENDING, "库内IP事实无法安全规范化，冲突结果为UNKNOWN", "提供有效IP字面量及冲突域所需站点引用", "Q31"));
                continue;
            }
            result.add(new IpKey(checked.canonical(), context.ips().scope() == IpScope.SITE ? fact.siteId() : null));
        }
        return result;
    }

    private static void address(ParsedRow row, Context context, String site, Set<IpKey> ips,
                                Map<Column, Object> normalized, List<Finding> findings) {
        normalized.put(Column.ADDRESS, null);
        String value = row.cells().get(Column.ADDRESS);
        if (blank(value) || context.rules() == null || context.rules().addressPolicy() == null) return;
        HostFormatValidator.Check checked = HostFormatValidator.check(value, context.rules().addressPolicy());
        if (!checked.valid()) {
            findings.add(rowFinding(Code.ADDRESS_INVALID, Column.ADDRESS, row, null, null,
                    checked.error(), checked.suggestion(), "M3-6")); return;
        }
        normalized.put(Column.ADDRESS, checked.canonical());
        if (checked.kind() == HostFormatValidator.Kind.DOMAIN || context.ips() == null
                || context.ips().scope() == null || context.ips().scopeDecisionReference() == null) return;
        String scopeSite = context.ips().scope() == IpScope.SITE ? site : null;
        if (context.ips().scope() == IpScope.SITE && site == null) {
            findings.add(rowFinding(Code.SITE_REFERENCE_UNRESOLVED, Column.SITE, row, null, null,
                    "站点冲突域缺少已核验的站点引用", "按已确认冲突域提供本租户站点", "AGENTS.md:租户授权", "Q31")); return;
        }
        if (ips.contains(new IpKey(checked.canonical(), scopeSite))) findings.add(rowFinding(Code.IP_CONFLICT,
                Column.ADDRESS, row, checked.canonical(), null, "IP与已提供冲突域内的已有设备冲突",
                "核对已有设备和已确认IP冲突规则后修正地址", "M3-6", "Q31"));
    }

    private static <T> Map<String, List<T>> index(Dictionary<T> dictionary, Rules rules) {
        Map<String, List<T>> result = new HashMap<>();
        if (dictionary == null || rules == null || rules.dictionaryKey() == null) return result;
        dictionary.entries().forEach((key, values) -> result.computeIfAbsent(rules.dictionaryKey().apply(key), unused -> new ArrayList<>()).addAll(values));
        return result;
    }

    private static String dictionaryKey(ParsedRow row, Column column, Rules rules) {
        return rules == null || rules.dictionaryKey() == null ? null : rules.dictionaryKey().apply(row.cells().get(column));
    }
    private static boolean complete(Dictionary<?> dictionary) { return dictionary != null && dictionary.complete(); }
    private static Finding ambiguous(ParsedRow row, Column column) {
        return rowFinding(Code.DICTIONARY_AMBIGUOUS, column, row, null, null, "词典别名未唯一解析",
                "提供唯一的已核验词典引用", "Q20", "Q31");
    }
    private static Finding unsafe() {
        return global(Code.TENANT_REFERENCE_UNSAFE, "词典或冲突事实不属于已授权租户", "重新查询已授权租户的安全引用事实",
                "AGENTS.md:租户授权", "03:租户联合定位");
    }
    private static Finding global(Code code, String reason, String suggestion, String... references) {
        return new Finding(code, null, null, null, null, reason, suggestion, List.of(references));
    }
    private static Finding rowFinding(Code code, Column column, ParsedRow row, Object normalized, Integer related,
                                       String reason, String suggestion, String... references) {
        return new Finding(code, column, row.cells().get(column), normalized, related, reason, suggestion, List.of(references));
    }
    private static Status status(List<Finding> findings) {
        if (findings.stream().anyMatch(f -> f.level() == Level.INPUT_BLOCKED)) return Status.INPUT_BLOCKED;
        if (findings.stream().anyMatch(f -> f.level() == Level.REFERENCE_BLOCKED)) return Status.REFERENCE_BLOCKED;
        if (findings.stream().anyMatch(f -> f.level() == Level.CONFIG_PENDING)) return Status.CONFIG_PENDING;
        if (findings.stream().anyMatch(f -> f.level() == Level.ERROR)) return Status.ERROR;
        if (findings.stream().anyMatch(f -> f.level() == Level.WARNING)) return Status.WARNING;
        return Status.PASS;
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void identifier(String value, String name) {
        if (blank(value) || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(name + " must be nonblank and contain no controls");
    }
    private static Map<Column, String> cells(Map<Column, String> input) {
        Objects.requireNonNull(input, "cells");
        EnumMap<Column, String> result = new EnumMap<>(Column.class);
        for (Column column : Column.values()) result.put(column, input.get(column));
        if (input.keySet().stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Null column");
        return Collections.unmodifiableMap(result);
    }
    private static Map<Column, Object> emptyNormalized() {
        EnumMap<Column, Object> result = new EnumMap<>(Column.class);
        for (Column column : Column.values()) result.put(column, null);
        return result;
    }
    private static Map<Column, Object> normalizedCells(Map<Column, Object> input) {
        Objects.requireNonNull(input, "normalized");
        EnumMap<Column, Object> result = new EnumMap<>(Column.class);
        for (Column column : Column.values()) {
            Object value = input.get(column);
            normalizedValue(value);
            result.put(column, value);
        }
        return Collections.unmodifiableMap(result);
    }
    private static void normalizedValue(Object value) {
        if (value != null && !(value instanceof String) && !(value instanceof LocalDate)) throw new IllegalArgumentException("Unsupported normalized cell type");
    }
}
