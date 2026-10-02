package cn.mop.domain.assetimport.source;

/** Technical containment values; these are not customer-approved business rules or an import SLA. */
public final class ImportLimits {
    private ImportLimits() {}
    public static final long MAX_SOURCE_BYTES=20L*1024*1024;
    public static final int MAX_DATA_ROWS=500,MAX_COLUMNS=64,MAX_SOURCE_ROW_INDEX=20_000,MAX_SHEETS=32,MAX_CELL_CHARS=4096;
    public static final int MAX_ZIP_ENTRIES=128,MAX_XML_DEPTH=64,MAX_XML_ELEMENTS=250_000,MAX_SHARED_STRINGS=10_000,MAX_STYLES=4096;
    public static final long MAX_PARSE_MILLIS=20_000;
    public static final long MAX_ZIP_ENTRY_BYTES=32L*1024*1024,MAX_EXPANDED_BYTES=64L*1024*1024,MAX_TOTAL_TEXT_CHARS=8L*1024*1024;
    public static final String RESOURCE_POLICY_VERSION="asset-import-technical-v1";
}
