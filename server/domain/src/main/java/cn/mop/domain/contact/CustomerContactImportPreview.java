package cn.mop.domain.contact;

import cn.mop.domain.DomainException;
import java.util.*;

/** Bounded, side-effect-free preview of explicit new-contact rows. Never an import authorization. */
public final class CustomerContactImportPreview {
    /** Technical request bound, not a contractual contact quota. */
    public static final int MAX_ROWS = 100;
    private CustomerContactImportPreview() {}

    public record QuietInput(Boolean enabled, String start, String end) {}
    public record Row(String name, List<String> roles, String mobile, String email, String wecomUserId,
                      List<String> preferredPriorities, QuietInput quietWindow, Boolean enabled) {
        public Row {
            // Preserve null members and duplicates until validation; never silently collapse them into a set.
            roles = frozen(roles);
            preferredPriorities = frozen(preferredPriorities);
        }
        private static List<String> frozen(List<String> values) {
            return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
        }
    }
    public record RowResult(int rowNumber, String status, String code, CustomerContact contact,
                            Integer identicalToRow) {}
    public record Result(List<RowResult> rows, int validRows, int invalidRows, int identicalRows) {
        public Result { rows = List.copyOf(rows); }
        public boolean canImport() { return false; }
        public String scope() { return "NEW_CONTACT_FORMAT_PREVIEW_ONLY"; }
        public String tenantAuthorization() { return "NOT_CHECKED"; }
        public String existingContactCheck() { return "NOT_RUN"; }
        public String lastDecisionMakerCheck() { return "NOT_RUN"; }
        public String notificationRoutingStatus() { return "CONFIG_PENDING"; }
        public boolean writesPerformed() { return false; }
        public boolean notificationDispatched() { return false; }
    }

    public static Result preview(List<Row> input) {
        if (input == null || input.isEmpty() || input.size() > MAX_ROWS) throw invalid();
        var source = new ArrayList<>(input);
        var rows = new ArrayList<RowResult>(source.size());
        var firstRows = new HashMap<CustomerContact, Integer>();
        int valid = 0, invalid = 0, identical = 0;
        for (int i = 0; i < source.size(); i++) {
            try {
                var contact = validate(source.get(i));
                Integer first = firstRows.putIfAbsent(contact, i + 1);
                if (first != null) identical++;
                // Identical content is a warning, not identity proof, merge authority or an automatic skip.
                rows.add(new RowResult(i + 1, "FORMAT_VALID", null, contact, first));
                valid++;
            } catch (DomainException failure) {
                if (!"VALIDATION_ERROR".equals(failure.code)) throw failure;
                rows.add(new RowResult(i + 1, "INVALID", "VALIDATION_ERROR", null, null));
                invalid++;
            }
        }
        return new Result(rows, valid, invalid, identical);
    }
    private static CustomerContact validate(Row row) {
        if (row == null || row.enabled() == null) throw invalid();
        var roles = values(row.roles(), CustomerContact.Role.class, false);
        var priorities = values(row.preferredPriorities(), CustomerContact.Priority.class, true);
        CustomerContact.QuietWindow quiet = null;
        if (row.quietWindow() != null) {
            var q = row.quietWindow();
            quiet = new CustomerContact.QuietWindow(q.enabled(),
                    q.start() == null ? null : CustomerContact.QuietWindow.parseMinute(q.start()),
                    q.end() == null ? null : CustomerContact.QuietWindow.parseMinute(q.end()));
        }
        return new CustomerContact(row.name(), roles, row.mobile(), row.email(), row.wecomUserId(),
                priorities, quiet, row.enabled());
    }
    private static <E extends Enum<E>> Set<E> values(List<String> source, Class<E> type, boolean nullable) {
        if (source == null) { if (nullable) return null; throw invalid(); }
        if (source.size() > type.getEnumConstants().length) throw invalid();
        Set<E> result = EnumSet.noneOf(type);
        for (String value : source) {
            if (value == null) throw invalid();
            E parsed;
            try { parsed = Enum.valueOf(type, value); } catch (IllegalArgumentException failure) { throw invalid(); }
            if (!result.add(parsed)) throw invalid();
        }
        return result;
    }
    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "联系人导入预览须提供1至100条明确记录；字段无效时不自动清洗、补值或写入");
    }
}
