package cn.mop.persistence.assetimport;

import cn.mop.domain.assetimport.AssetImportPrechecker;
import cn.mop.domain.assetimport.AssetImportPrechecker.*;
import cn.mop.domain.assetimport.HostFormatValidator;
import cn.mop.domain.assetimport.HostFormatValidator.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Standalone, real JDK 17 assertions. Synthetic authoritative inputs; no file, repository or integration I/O. */
public final class AssetImportPrecheckChecks {
    private static final String TENANT = "synthetic-tenant-a";
    private static final String OTHER = "synthetic-tenant-b";
    private static int assertions;
    private static long elapsed500Nanos;
    private AssetImportPrecheckChecks() {}

    public static void main(String[] arguments) {
        fifteenColumnsAndExplicitRequiredFields();
        fiveContractErrors();
        threeContractWarnings();
        incompleteFactsAndRulesNeverPass();
        tenantReferencesFailClosed();
        siteSafetyIsNotAContractWarning();
        suppliedNameEqualityAndSourceRowNumbers();
        strictDatesAndAmbiguity();
        bareAddressFormatsAndCanonicalization();
        explicitIpConflictFactsAndScopes();
        defensiveCopiesAndNulls();
        rowLimitAndMalformedInput();
        fiftyRowsWithThreeErrorClasses();
        fiveHundredPurePrecheckTiming();
        System.out.println("{\"status\":\"PASS\",\"groups\":14,\"assertions\":" + assertions
                + ",\"scope\":\"pure_parsed_asset_import_precheck\",\"synthetic_inputs\":true"
                + ",\"rows50\":50,\"error_classes50\":3,\"blocked_rows50\":3,\"rows500\":500"
                + ",\"pure_precheck_500_elapsed_ns\":" + elapsed500Nanos
                + ",\"junit\":\"NOT_RUN\",\"integration\":\"NOT_RUN\",\"full_import_60s_acceptance\":\"NOT_RUN\"}");
    }

    private static final class Inputs {
        Dictionary<String> types = dictionary(true, Map.of("设备测试类", List.of("synthetic-device-type")));
        Dictionary<TenantReference> sites = dictionary(true, Map.of("测试站点", List.of(new TenantReference(TENANT, "site-a"))));
        Dictionary<TenantReference> members = dictionary(true, Map.of("测试成员", List.of(new TenantReference(TENANT, "member-a"))));
        Dictionary<Boolean> brands = dictionary(true, Map.of("测试已映射品牌", List.of(true), "测试未映射品牌", List.of(false)));
        Rules rules = rules(TextRule.STRIP, dates(List.of("uuuu-MM-dd"), DateAmbiguity.REQUIRE_UNIQUE_DATE));
        NameFacts names = new NameFacts(TENANT, true, Set.of());
        IpFacts ips = new IpFacts(TENANT, true, IpScope.TENANT, "synthetic-rule-not-customer-decision", List.of());
        Context context() { return new Context(new AuthorizedTenant(TENANT, "synthetic-authorization-not-authentication"), types, sites, members, brands, rules, names, ips); }
    }

    private static Rules rules(TextRule name, DateRules dates) {
        return new Rules(new RequiredFields(true, Set.of(Column.NAME, Column.TYPE, Column.ADDRESS)),
                name, TextRule.STRIP, TextRule.STRIP, false, policy(DomainMode.ASCII_OR_ACE), dates);
    }
    private static DateRules dates(List<String> patterns, DateAmbiguity ambiguity) { return new DateRules(true, patterns, ambiguity); }
    private static Policy policy(DomainMode mode) {
        return new Policy(true, mode, false, RootDotMode.REJECT, false, LeadingZeroMode.REJECT,
                MappedIpv4Mode.PRESERVE_IPV6, WhitespaceMode.STRIP, NumericDomainMode.REJECT_LEGACY_ADDRESS_SHAPES);
    }
    private static <T> Dictionary<T> dictionary(boolean complete, Map<String, List<T>> entries) { return new Dictionary<>(TENANT, complete, entries); }
    private static ParsedRow row(int number) {
        EnumMap<Column, String> cells = new EnumMap<>(Column.class);
        cells.put(Column.NAME, "测试设备-" + number); cells.put(Column.TYPE, "设备测试类");
        cells.put(Column.ADDRESS, "192.0.2." + (number % 250 + 1)); cells.put(Column.BRAND, "测试已映射品牌");
        cells.put(Column.SITE, "测试站点"); cells.put(Column.OWNER, "测试成员"); cells.put(Column.NOTE, "虚构测试备注");
        return new ParsedRow(number, cells);
    }
    private static ParsedRow change(ParsedRow row, Column column, String value) {
        EnumMap<Column, String> cells = new EnumMap<>(Column.class); cells.putAll(row.cells()); cells.put(column, value);
        return new ParsedRow(row.rowNumber(), cells);
    }
    private static Result check(Inputs inputs, ParsedRow... rows) { return AssetImportPrechecker.check(inputs.context(), List.of(rows)); }
    private static RowResult checked(Inputs inputs, ParsedRow row) { return check(inputs, row).rows().get(0); }

    private static void fifteenColumnsAndExplicitRequiredFields() {
        equal(15, Column.values().length, "Contract has exactly fifteen columns");
        equal(List.of("设备名称", "设备类型", "IP或域名", "品牌", "型号", "序列号", "站点", "位置", "负责人", "所属业务", "采购日期", "维保到期日", "超期服役日期", "采集方式", "备注"),
                java.util.Arrays.stream(Column.values()).map(Column::label).toList(), "Contract column order");
        RowResult result = checked(new Inputs(), row(1));
        equal(Status.PASS, result.status(), "Unfilled optional fields do not become fifteen mandatory fields");
        equal(15, result.original().size(), "Missing columns retain explicit null slots");
        equal(null, result.original().get(Column.MODEL), "Absent optional original stays null");
        equal(null, result.normalized().get(Column.PURCHASE_DATE), "Absent date stays null");
        Inputs inputs = new Inputs();
        Rules original = inputs.rules;
        inputs.rules = new Rules(new RequiredFields(true, Set.of()), original.nameKey(), original.dictionaryKey(), original.otherText(), original.allowMissingSite(), original.addressPolicy(), original.dateRules());
        RowResult missing = checked(inputs, change(row(2), Column.NOTE, null));
        has(missing, Code.REQUIRED_MISSING, Column.NOTE);
        equal(Status.ERROR, missing.status(), "Contract note remains required despite empty supplied set");
        inputs.rules = new Rules(new RequiredFields(true, Set.of(Column.MODEL)), original.nameKey(), original.dictionaryKey(), original.otherText(), original.allowMissingSite(), original.addressPolicy(), original.dateRules());
        has(checked(inputs, row(2)), Code.REQUIRED_MISSING, Column.MODEL);
    }

    private static void fiveContractErrors() {
        Inputs inputs = new Inputs();
        inputs.names = new NameFacts(TENANT, true, Set.of("已有名称"));
        inputs.ips = new IpFacts(TENANT, true, IpScope.TENANT, "synthetic-scope", List.of(new ExistingIp("198.51.100.7", null)));
        List<ParsedRow> rows = List.of(change(row(2), Column.NOTE, "  "), change(row(3), Column.ADDRESS, "999.1.2.3"),
                change(row(4), Column.NAME, "同名"), change(row(9), Column.NAME, "同名"),
                change(row(10), Column.ADDRESS, "198.51.100.7"), change(row(11), Column.TYPE, "未列入类型"),
                change(row(12), Column.NAME, "已有名称"));
        Result result = AssetImportPrechecker.check(inputs.context(), rows);
        equal(Status.ERROR, result.status(), "Known contract errors block their rows");
        has(result.rows().get(0), Code.REQUIRED_MISSING, Column.NOTE);
        has(result.rows().get(1), Code.ADDRESS_INVALID, Column.ADDRESS);
        has(result.rows().get(3), Code.NAME_DUPLICATE, Column.NAME);
        Finding duplicate = finding(result.rows().get(3), Code.NAME_DUPLICATE);
        equal(4, duplicate.relatedRow(), "Duplicate points to original source row");
        equal("与第4行重复", duplicate.reason(), "Contract duplicate-row reason");
        has(result.rows().get(4), Code.IP_CONFLICT, Column.ADDRESS);
        has(result.rows().get(5), Code.TYPE_NOT_ALLOWED, Column.TYPE);
        has(result.rows().get(6), Code.NAME_DUPLICATE, Column.NAME);
        for (RowResult row : result.rows()) for (Finding item : row.findings()) {
            equal(Level.ERROR, item.level(), "Exactly contract error levels in this scenario");
            truth(item.suggestion() != null && !item.suggestion().isBlank(), "Every error includes correction suggestion");
            equal(row.original().get(item.column()), item.original(), "Receipt retains original value");
        }
    }

    private static void threeContractWarnings() {
        ParsedRow input = change(change(change(row(1), Column.PURCHASE_DATE, "2026-02-30"), Column.OWNER, "未知成员"), Column.BRAND, "测试未映射品牌");
        RowResult result = checked(new Inputs(), input);
        equal(Status.WARNING, result.status(), "Only three contract warning classes");
        equal(3, result.findings().size(), "No invented warning class");
        has(result, Code.DATE_INVALID, Column.PURCHASE_DATE); has(result, Code.OWNER_NOT_MEMBER, Column.OWNER); has(result, Code.BRAND_NO_OID, Column.BRAND);
        equal(null, result.normalized().get(Column.PURCHASE_DATE), "Invalid date cleared");
        equal(null, result.normalized().get(Column.OWNER), "Nonmember cleared");
        equal("测试未映射品牌", result.normalized().get(Column.BRAND), "Unmapped brand retained");
        truth(finding(result, Code.BRAND_NO_OID).reason().contains("仅提供可用性"), "Mapping limitation is visible");
        for (Finding warning : result.findings()) equal(Level.WARNING, warning.level(), "Warning is distinct from blocker");
        RowResult absent = checked(new Inputs(), change(row(2), Column.BRAND, "完整查询未找到品牌"));
        has(absent, Code.BRAND_NO_OID, Column.BRAND);
    }

    private static void incompleteFactsAndRulesNeverPass() {
        for (int variation = 0; variation < 12; variation++) {
            Inputs inputs = new Inputs();
            switch (variation) {
                case 0 -> inputs.types = dictionary(false, inputs.types.entries());
                case 1 -> inputs.sites = dictionary(false, inputs.sites.entries());
                case 2 -> inputs.members = dictionary(false, inputs.members.entries());
                case 3 -> inputs.brands = dictionary(false, inputs.brands.entries());
                case 4 -> inputs.names = new NameFacts(TENANT, false, Set.of());
                case 5 -> inputs.ips = new IpFacts(TENANT, false, IpScope.TENANT, "synthetic", List.of());
                case 6 -> inputs.ips = new IpFacts(TENANT, true, null, null, List.of());
                case 7 -> inputs.types = null;
                case 8 -> inputs.rules = null;
                case 9 -> inputs.rules = new Rules(new RequiredFields(false, Set.of()), TextRule.STRIP, TextRule.STRIP, TextRule.STRIP, false, policy(DomainMode.ASCII_OR_ACE), dates(List.of("uuuu-MM-dd"), DateAmbiguity.REQUIRE_UNIQUE_DATE));
                case 10 -> inputs.rules = new Rules(inputs.rules.requiredFields(), TextRule.STRIP, TextRule.STRIP, TextRule.STRIP, false, null, inputs.rules.dateRules());
                case 11 -> inputs.rules = new Rules(inputs.rules.requiredFields(), TextRule.STRIP, TextRule.STRIP, TextRule.STRIP, false, inputs.rules.addressPolicy(), new DateRules(false, List.of(), DateAmbiguity.REQUIRE_UNIQUE_DATE));
                default -> throw new AssertionError("Unexpected case");
            }
            Result result = check(inputs, row(1));
            truth(result.status() == Status.CONFIG_PENDING || result.status() == Status.REFERENCE_BLOCKED, "Incomplete prerequisites cannot pass");
            truth(result.batchFindings().stream().anyMatch(f -> f.level() == Level.CONFIG_PENDING), "Unknown completeness is explicit");
        }
        Inputs inputs = new Inputs(); inputs.members = dictionary(false, Map.of()); inputs.brands = dictionary(false, Map.of()); inputs.types = dictionary(false, Map.of());
        RowResult row = checked(inputs, change(row(1), Column.TYPE, "未查询类型"));
        equal(Status.CONFIG_PENDING, row.status(), "Unknown query stays pending");
        lacks(row, Code.OWNER_NOT_MEMBER); lacks(row, Code.BRAND_NO_OID); lacks(row, Code.TYPE_NOT_ALLOWED);
        RowResult erroneous = checked(inputs, change(change(row(1), Column.ADDRESS, "1.2.3"), Column.NOTE, null));
        has(erroneous, Code.ADDRESS_INVALID, Column.ADDRESS); has(erroneous, Code.REQUIRED_MISSING, Column.NOTE);
        equal(Status.CONFIG_PENDING, erroneous.status(), "Known errors survive pending overall state");
        inputs = new Inputs(); inputs.ips = new IpFacts(TENANT, true, IpScope.TENANT, null, List.of());
        equal(Status.CONFIG_PENDING, check(inputs, row(1)).status(), "Scope without decision evidence stays pending");
    }

    private static void tenantReferencesFailClosed() {
        for (int variation = 0; variation < 7; variation++) {
            Inputs inputs = new Inputs();
            switch (variation) {
                case 0 -> inputs.types = new Dictionary<>(OTHER, true, inputs.types.entries());
                case 1 -> inputs.sites = new Dictionary<>(OTHER, true, inputs.sites.entries());
                case 2 -> inputs.members = dictionary(true, Map.of("测试成员", List.of(new TenantReference(OTHER, "member-secret-other"))));
                case 3 -> inputs.sites = dictionary(true, Map.of("测试站点", List.of(new TenantReference(OTHER, "site-secret-other"))));
                case 4 -> inputs.brands = new Dictionary<>(OTHER, true, inputs.brands.entries());
                case 5 -> inputs.names = new NameFacts(OTHER, true, Set.of("other-name"));
                case 6 -> inputs.ips = new IpFacts(OTHER, true, IpScope.TENANT, "synthetic", List.of());
                default -> throw new AssertionError("Unexpected case");
            }
            Result result = check(inputs, row(1));
            equal(Status.REFERENCE_BLOCKED, result.status(), "Cross-tenant facts fail closed");
            has(result.rows().get(0), Code.TENANT_REFERENCE_UNSAFE, null);
            equal(null, result.rows().get(0).normalized().get(Column.SITE), "Foreign references never normalized");
            equal(null, result.rows().get(0).normalized().get(Column.OWNER), "Foreign owner never normalized");
            truth(result.batchFindings().get(0).references().contains("AGENTS.md:租户授权"), "Safety prerequisite is cited");
            lacks(result.rows().get(0), Code.OWNER_NOT_MEMBER);
        }
        rejects(() -> new AuthorizedTenant(" ", "evidence"), "No implicit authorized tenant");
        rejects(() -> new AuthorizedTenant(TENANT, null), "Authorization evidence must be explicit");
    }

    private static void siteSafetyIsNotAContractWarning() {
        RowResult missing = checked(new Inputs(), change(row(1), Column.SITE, "无法解析站点"));
        equal(Status.REFERENCE_BLOCKED, missing.status(), "Unknown site blocks safety"); has(missing, Code.SITE_REFERENCE_UNRESOLVED, Column.SITE);
        equal(Level.REFERENCE_BLOCKED, finding(missing, Code.SITE_REFERENCE_UNRESOLVED).level(), "Site blocker is not invented contract error or warning");
        equal(null, missing.normalized().get(Column.SITE), "Unknown site is never copied as object reference");
        Inputs inputs = new Inputs(); inputs.sites = dictionary(true, Map.of("测试站点", List.of(new TenantReference(TENANT, "s1"), new TenantReference(TENANT, "s2"))));
        equal(Status.REFERENCE_BLOCKED, checked(inputs, row(1)).status(), "Ambiguous site cannot select first result");
        inputs = new Inputs();
        Rules previous = inputs.rules;
        inputs.rules = new Rules(previous.requiredFields(), previous.nameKey(), previous.dictionaryKey(), previous.otherText(), true, previous.addressPolicy(), previous.dateRules());
        equal(Status.PASS, checked(inputs, change(row(1), Column.SITE, null)).status(), "Explicit missing-site policy is respected for tenant conflict scope");
        inputs.ips = new IpFacts(TENANT, true, IpScope.SITE, "synthetic", List.of());
        equal(Status.REFERENCE_BLOCKED, checked(inputs, change(row(1), Column.SITE, null)).status(), "Site conflict scope requires safe site even if optional");
    }

    private static void suppliedNameEqualityAndSourceRowNumbers() {
        Inputs inputs = new Inputs();
        ParsedRow first = change(row(10), Column.NAME, " Name "); ParsedRow second = change(row(24), Column.NAME, "Name");
        inputs.rules = rules(TextRule.EXACT, inputs.rules.dateRules());
        equal(Status.PASS, check(inputs, first, second).status(), "Exact policy does not silently trim names");
        inputs.rules = rules(TextRule.STRIP, inputs.rules.dateRules());
        Result duplicate = check(inputs, first, second);
        equal(10, finding(duplicate.rows().get(1), Code.NAME_DUPLICATE).relatedRow(), "Not array index: original row number");
        equal(" Name ", duplicate.rows().get(0).original().get(Column.NAME), "Raw whitespace retained");
        equal("Name", duplicate.rows().get(0).normalized().get(Column.NAME), "Supplied strip normalization");
        truth(duplicate.rows().get(0).normalizationSuggestions().get(Column.NAME) != null, "Canonical changes have suggestions");
        equal(Status.PASS, check(inputs, change(row(1), Column.NAME, "Name"), change(row(2), Column.NAME, "name")).status(), "STRIP preserves case");
        inputs.rules = rules(TextRule.STRIP_LOWERCASE, inputs.rules.dateRules());
        equal(Status.ERROR, check(inputs, change(row(1), Column.NAME, "Name"), change(row(2), Column.NAME, "name")).status(), "Explicit case folding affects equality");
        inputs.names = new NameFacts(TENANT, true, Set.of(" NAME "));
        has(checked(inputs, change(row(1), Column.NAME, "name")), Code.NAME_DUPLICATE, Column.NAME);
        inputs.names = new NameFacts(TENANT, true, Set.of());
        equal(Status.PASS, check(inputs, change(row(1), Column.NAME, "Ａ"), change(row(2), Column.NAME, "A")).status(), "No invented NFKC equality");
        equal(check(inputs, row(1)), check(inputs, row(1)), "Repeated computation is deterministic and does not add state");
    }

    private static void strictDatesAndAmbiguity() {
        RowResult valid = checked(new Inputs(), change(row(1), Column.PURCHASE_DATE, "2024-02-29"));
        equal(LocalDate.of(2024, 2, 29), valid.normalized().get(Column.PURCHASE_DATE), "Valid leap day is LocalDate");
        for (String invalid : List.of("2023-02-29", "2026-13-01", "2026-04-31", "not-date", "0", "45200")) {
            RowResult result = checked(new Inputs(), change(row(1), Column.PURCHASE_DATE, invalid));
            has(result, Code.DATE_INVALID, Column.PURCHASE_DATE); equal(null, result.normalized().get(Column.PURCHASE_DATE), "Invalid date never becomes epoch");
        }
        Inputs inputs = new Inputs(); inputs.rules = rules(TextRule.STRIP, dates(List.of("dd/MM/uuuu", "MM/dd/uuuu"), DateAmbiguity.REQUIRE_UNIQUE_DATE));
        RowResult ambiguous = checked(inputs, change(row(1), Column.PURCHASE_DATE, "01/02/2026"));
        equal(Status.CONFIG_PENDING, ambiguous.status(), "Ambiguous date does not silently choose locale"); has(ambiguous, Code.DATE_AMBIGUOUS, Column.PURCHASE_DATE);
        equal(null, ambiguous.normalized().get(Column.PURCHASE_DATE), "Ambiguous date absent");
        inputs.rules = rules(TextRule.STRIP, dates(List.of("dd/MM/uuuu", "MM/dd/uuuu"), DateAmbiguity.FIRST_PATTERN));
        equal(LocalDate.of(2026, 2, 1), checked(inputs, change(row(1), Column.PURCHASE_DATE, "01/02/2026")).normalized().get(Column.PURCHASE_DATE), "Explicit pattern priority governs date");
        inputs.rules = rules(TextRule.STRIP, dates(List.of("uuuu-MM-dd"), DateAmbiguity.REQUIRE_UNIQUE_DATE));
        equal(LocalDate.of(1970, 1, 1), checked(inputs, change(row(1), Column.PURCHASE_DATE, "1970-01-01")).normalized().get(Column.PURCHASE_DATE), "An explicitly supplied valid epoch day remains valid");
    }

    private static void bareAddressFormatsAndCanonicalization() {
        Policy policy = policy(DomainMode.ASCII_OR_ACE);
        for (String valid : List.of("0.0.0.0", "255.255.255.255", "192.0.2.10", "::", "::1", "2001:db8::1", "1:2:3:4:5:6:7:8", "::ffff:192.0.2.128", "router.example", "XN--BCHER-KVA.example")) {
            Check result = HostFormatValidator.check(valid, policy);
            truth(result.valid(), "Valid bare address"); equal(valid, result.original(), "Original bare address retained"); truth(result.canonical() != null, "Valid canonical address present");
        }
        equal("2001:db8:0:0:0:0:0:1", HostFormatValidator.check("2001:DB8::1", policy).canonical(), "Deterministic numerical IPv6 normalization");
        equal(HostFormatValidator.check("2001:db8::1", policy).canonical(), HostFormatValidator.check("2001:0db8:0:0:0:0:0:1", policy).canonical(), "Equivalent IPv6 forms normalize equally");
        equal("router.example", HostFormatValidator.check(" Router.Example ", policy).canonical(), "Explicit strip and DNS case normalization");
        for (String invalid : List.of("256.1.1.1", "1.2.3", "1.2.3.4.5", "-1.2.3.4", "127.1", "2130706433", "0x7f000001", "01.2.3.4", "1::2::3", ":::" , "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "::ffff:999.0.2.1", "_host.example", "a..example", "-a.example", "a-.example", "https://router.example", "router.example:443", "[::1]:443", "192.0.2.1/24", "router.example/path", "user@router.example", "router.example?x=1", "router.example#x", "router%2eexample", "router .example", "router\n.example", "[::1]", "fe80::1%eth0", "localhost", "example.com.", "xn--.example", "xn--invalid-.example", "a".repeat(64) + ".example", "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(62), "１２７.０.０.１", "example\u3002com", "example\uff0ecom")) {
            Check result = HostFormatValidator.check(invalid, policy);
            truth(!result.valid(), "Invalid or unapproved address form rejected"); equal(null, result.canonical(), "Invalid host cannot be canonicalized"); truth(result.suggestion() != null, "Format failure has suggestion");
        }
        Policy unicode = policy(DomainMode.UNICODE_ROUNDTRIP);
        equal("xn--bcher-kva.example", HostFormatValidator.check("bücher.example", unicode).canonical(), "Approved roundtrip IDN uses JDK offline IDN conversion");
        truth(!HostFormatValidator.check("faß.de", unicode).valid(), "IDNA2003 lossy sharp-s mapping rejected");
        truth(!HostFormatValidator.check("e\u0301.example", unicode).valid(), "Non-NFC IDN does not silently normalize");
        truth(!HostFormatValidator.check("ｆｏｏ.example", unicode).valid(), "Compatibility mapping ambiguity rejected");
        truth(!HostFormatValidator.check("bücher.example", policy).valid(), "Unicode requires explicit IDN policy");
        truth(!HostFormatValidator.check("xn--bcher-kva.example", policy(DomainMode.ASCII_ONLY)).valid(), "ASCII-only policy excludes IDN ACE");
        Policy optional = new Policy(true, DomainMode.ASCII_OR_ACE, true, RootDotMode.REMOVE, true,
                LeadingZeroMode.DECIMAL, MappedIpv4Mode.CANONICAL_IPV4, WhitespaceMode.REJECT, NumericDomainMode.REJECT_LEGACY_ADDRESS_SHAPES);
        equal("1.2.3.4", HostFormatValidator.check("01.2.3.4", optional).canonical(), "Leading-zero decimal is explicit policy");
        equal("localhost", HostFormatValidator.check("localhost", optional).canonical(), "Single-label policy explicit");
        equal("example.com", HostFormatValidator.check("example.com.", optional).canonical(), "Trailing-dot equivalence explicit");
        equal("0:0:0:0:0:0:0:1", HostFormatValidator.check("[::1]", optional).canonical(), "Bracket wrapper policy explicit");
        equal("192.0.2.128", HostFormatValidator.check("::ffff:192.0.2.128", optional).canonical(), "Mapped IPv4 equivalence policy explicit");
        truth(!HostFormatValidator.check(" router.example ", optional).valid(), "Whitespace behavior is policy driven");
        for (String numeric : List.of("0x7f000001.", "0x7f.0.0.1", "0x7f.0.0.1.", "127.0x1", "0177.0.0.1.", "2130706433.", "127.1.")) {
            truth(!HostFormatValidator.check(numeric, optional).valid(), "Legacy numerical address ambiguity rejected after canonicalization");
        }
        Policy preserve = new Policy(true, DomainMode.ASCII_OR_ACE, true, RootDotMode.PRESERVE, false,
                LeadingZeroMode.REJECT, MappedIpv4Mode.PRESERVE_IPV6, WhitespaceMode.STRIP, NumericDomainMode.REJECT_LEGACY_ADDRESS_SHAPES);
        equal("router.example.", HostFormatValidator.check("Router.Example.", preserve).canonical(), "Explicit root-dot preservation keeps absolute DNS form");
        Policy alphanumeric = new Policy(true, DomainMode.ASCII_OR_ACE, true, RootDotMode.REMOVE, false,
                LeadingZeroMode.REJECT, MappedIpv4Mode.PRESERVE_IPV6, WhitespaceMode.STRIP, NumericDomainMode.ALLOW_ALPHANUMERIC_LABELS);
        Check hexName = HostFormatValidator.check("0x7f000001.", alphanumeric);
        truth(hexName.valid(), "Explicit alphanumeric-domain policy is only syntax acceptance, never resolution permission");
        truth(HostFormatValidator.check(hexName.canonical(), alphanumeric).valid(), "Canonical host remains accepted under identical supplied policy");
    }

    private static void explicitIpConflictFactsAndScopes() {
        Inputs inputs = new Inputs(); inputs.ips = new IpFacts(TENANT, true, IpScope.TENANT, "synthetic", List.of(new ExistingIp("2001:0db8::1", null)));
        has(checked(inputs, change(row(1), Column.ADDRESS, "2001:db8:0:0:0:0:0:1")), Code.IP_CONFLICT, Column.ADDRESS);
        equal(Status.PASS, checked(inputs, change(row(1), Column.ADDRESS, "router.example")).status(), "Domains are syntax checked only and never resolved into conflict facts");
        inputs.ips = new IpFacts(TENANT, true, IpScope.SITE, "synthetic", List.of(new ExistingIp("192.0.2.2", "site-b")));
        equal(Status.PASS, checked(inputs, row(1)).status(), "Different-site IP does not conflict under supplied SITE rule");
        inputs.ips = new IpFacts(TENANT, true, IpScope.SITE, "synthetic", List.of(new ExistingIp("192.0.2.2", "site-a")));
        has(checked(inputs, row(1)), Code.IP_CONFLICT, Column.ADDRESS);
        inputs.ips = new IpFacts(TENANT, true, IpScope.PLATFORM, "synthetic-explicit-platform-query", List.of(new ExistingIp("192.0.2.2", "site-b")));
        has(checked(inputs, row(1)), Code.IP_CONFLICT, Column.ADDRESS);
        inputs.ips = new IpFacts(TENANT, false, IpScope.TENANT, "synthetic", List.of(new ExistingIp("192.0.2.2", null)));
        RowResult partial = checked(inputs, row(1)); equal(Status.CONFIG_PENDING, partial.status(), "Known match plus incomplete query retains UNKNOWN completeness"); has(partial, Code.IP_CONFLICT, Column.ADDRESS);
        for (ExistingIp invalid : List.of(new ExistingIp("router.example", null), new ExistingIp("1.2.3", null))) {
            inputs.ips = new IpFacts(TENANT, true, IpScope.TENANT, "synthetic", List.of(invalid));
            equal(Status.CONFIG_PENDING, checked(inputs, row(1)).status(), "Malformed database IP fact cannot establish NO_MATCH");
        }
        inputs.ips = new IpFacts(TENANT, true, IpScope.SITE, "synthetic", List.of(new ExistingIp("192.0.2.2", null)));
        equal(Status.CONFIG_PENDING, checked(inputs, row(1)).status(), "SITE query missing site facts remains pending");
        inputs = new Inputs();
        Result sameBatchIp = check(inputs, change(row(1), Column.ADDRESS, "192.0.2.5"), change(row(2), Column.ADDRESS, "192.0.2.5"));
        equal(Status.PASS, sameBatchIp.status(), "No invented within-file IP conflict rule beyond supplied database facts");
    }

    private static void defensiveCopiesAndNulls() {
        EnumMap<Column, String> raw = new EnumMap<>(Column.class); raw.putAll(row(1).cells());
        ParsedRow parsed = new ParsedRow(1, raw); raw.put(Column.NOTE, "changed-after-construction");
        equal("虚构测试备注", parsed.cells().get(Column.NOTE), "Parsed row detached from input map");
        List<String> types = new ArrayList<>(List.of("synthetic-type")); Map<String, List<String>> dictionary = new HashMap<>(); dictionary.put("设备测试类", types);
        Inputs inputs = new Inputs(); inputs.types = dictionary(true, dictionary); types.clear(); dictionary.clear();
        equal("synthetic-type", checked(inputs, parsed).normalized().get(Column.TYPE), "Dictionary map and nested values detached");
        Set<String> names = new HashSet<>(Set.of("测试设备-1")); inputs.names = new NameFacts(TENANT, true, names); names.clear();
        has(checked(inputs, parsed), Code.NAME_DUPLICATE, Column.NAME);
        RowResult result = checked(new Inputs(), parsed);
        rejects(() -> result.original().put(Column.MODEL, "mutation"), "Original output immutable");
        rejects(() -> result.normalized().put(Column.MODEL, "mutation"), "Normalized output immutable");
        rejects(() -> result.findings().add(null), "Findings immutable");
        rejects(() -> result.normalizationSuggestions().put(Column.NAME, "mutation"), "Normalization suggestions immutable");
        Result batch = check(new Inputs(), parsed);
        rejects(() -> batch.rows().clear(), "Result rows immutable"); rejects(() -> batch.batchFindings().clear(), "Batch findings immutable");
        equal(null, result.normalized().get(Column.WARRANTY_EXPIRY), "Null date distinct from zero or epoch");
        equal(15, result.normalizationSuggestions().size(), "All contract cells retain null/explicit suggestions");
        rejects(() -> new Finding(Code.DATE_INVALID, Column.PURCHASE_DATE, null, new ArrayList<>(), null,
                "synthetic", "synthetic", List.of("M3-6")), "Mutable arbitrary normalized finding values rejected");
    }

    private static void rowLimitAndMalformedInput() {
        List<ParsedRow> rows = new ArrayList<>(); for (int number = 1; number <= 501; number++) rows.add(row(number));
        Result tooMany = AssetImportPrechecker.check(new Inputs().context(), rows);
        equal(Status.INPUT_BLOCKED, tooMany.status(), "501 data rows rejected"); equal(0, tooMany.rows().size(), "Oversized batch not partially processed");
        equal(Code.TOO_MANY_ROWS, tooMany.batchFindings().get(0).code(), "Contract row cap identified");
        Result duplicateNumbers = check(new Inputs(), row(9), change(row(9), Column.NAME, "distinct-name"));
        equal(Status.INPUT_BLOCKED, duplicateNumbers.status(), "Duplicate original row numbers rejected");
        equal(Code.DUPLICATE_ROW_NUMBER, duplicateNumbers.batchFindings().get(0).code(), "Source identity failure identified");
        rejects(() -> new ParsedRow(0, Map.of()), "Zero row number rejected"); rejects(() -> new ParsedRow(-1, Map.of()), "Negative row number rejected");
        rejects(() -> AssetImportPrechecker.check(new Inputs().context(), null), "Null batch rejected");
        List<ParsedRow> nullRows = new ArrayList<>(); nullRows.add(null); rejects(() -> AssetImportPrechecker.check(new Inputs().context(), nullRows), "Null row rejected");
        Map<Column, String> nullColumn = new HashMap<>(); nullColumn.put(null, "not-allowed"); rejects(() -> new ParsedRow(1, nullColumn), "Unknown null column rejected");
        equal(Status.PASS, AssetImportPrechecker.check(new Inputs().context(), List.of()).status(), "Empty pure calculation has no import side effect or acceptance meaning");
        Inputs incomplete = new Inputs(); incomplete.names = null;
        equal(Status.CONFIG_PENDING, AssetImportPrechecker.check(incomplete.context(), List.of()).status(), "Empty input cannot bypass prerequisite completeness");
    }

    private static void fiftyRowsWithThreeErrorClasses() {
        List<ParsedRow> rows = new ArrayList<>();
        for (int number = 1; number <= 50; number++) {
            ParsedRow row = row(number);
            if (number == 7) row = change(row, Column.NOTE, null);
            if (number == 19) row = change(row, Column.ADDRESS, "300.2.3.4");
            if (number == 43) row = change(row, Column.TYPE, "未知类型");
            rows.add(row);
        }
        Result result = AssetImportPrechecker.check(new Inputs().context(), rows);
        equal(50, result.rows().size(), "All fifty original rows preserved");
        int errors = 0;
        for (RowResult row : result.rows()) {
            Code expected = switch (row.rowNumber()) { case 7 -> Code.REQUIRED_MISSING; case 19 -> Code.ADDRESS_INVALID; case 43 -> Code.TYPE_NOT_ALLOWED; default -> null; };
            equal(expected == null ? Status.PASS : Status.ERROR, row.status(), "Fifty-row scenario classification");
            if (expected != null) { errors++; equal(1, row.findings().size(), "One accurate error per scenario error row"); equal(expected, row.findings().get(0).code(), "Fifty-row scenario reason"); }
            equal(rows.get(row.rowNumber() - 1).cells(), row.original(), "Fifty-row receipt original values");
        }
        equal(3, errors, "Three error rows blocked with exact source locations");
        equal(result, AssetImportPrechecker.check(new Inputs().context(), rows), "Repeat pure precheck has no stateful duplicate creation");
    }

    private static void fiveHundredPurePrecheckTiming() {
        List<ParsedRow> rows = new ArrayList<>(); for (int number = 1; number <= 500; number++) rows.add(row(number));
        Context context = new Inputs().context();
        AssetImportPrechecker.check(context, rows);
        long started = System.nanoTime(); Result result = AssetImportPrechecker.check(context, rows); elapsed500Nanos = System.nanoTime() - started;
        equal(Status.PASS, result.status(), "500 accepted by pure precheck under synthetic supplied rules"); equal(500, result.rows().size(), "Exactly five hundred result rows");
        for (RowResult row : result.rows()) { equal(Status.PASS, row.status(), "500-row status"); equal(15, row.normalized().size(), "500-row fixed columns"); }
        truth(elapsed500Nanos >= 0, "Monotonic pure-calculation timing reported without claiming full import SLA");
    }

    private static Finding finding(RowResult row, Code code) {
        return row.findings().stream().filter(item -> item.code() == code).findFirst().orElseThrow(() -> new AssertionError("Expected finding code " + code));
    }
    private static void has(RowResult row, Code code, Column column) {
        Finding finding = finding(row, code); equal(column, finding.column(), "Finding attached to contract column");
    }
    private static void lacks(RowResult row, Code code) { truth(row.findings().stream().noneMatch(item -> item.code() == code), "Unknown facts cannot manufacture finding " + code); }
    private static void truth(boolean value, String description) { assertions++; if (!value) throw new AssertionError(description); }
    private static void equal(Object expected, Object actual, String description) { assertions++; if (!Objects.equals(expected, actual)) throw new AssertionError(description); }
    private static void rejects(Runnable command, String description) {
        assertions++;
        try { command.run(); } catch (IllegalArgumentException | NullPointerException | UnsupportedOperationException exception) { return; }
        throw new AssertionError(description);
    }
}
