package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetimport.source.AssetImportSchema;
import cn.mop.domain.assetimport.source.ImportFormat;
import cn.mop.domain.assetimport.source.ImportLimits;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static cn.mop.api.ApiResponse.map;

/** Contract file formats and explicit technical containment; neither approves production nor imports assets. */
public final class AssetImportPolicy {
    public static final String PURPOSE="asset_import_source";
    private final boolean configured;
    public AssetImportPolicy(){this("CSV,XLSX");}
    public AssetImportPolicy(String allowedFormats){configured="CSV,XLSX".equals(allowedFormats);}
    public boolean configured(){return configured;}

    public Map<String,Object> dto() {
        return map("purpose",PURPOSE,"allowed_formats",List.of("CSV","XLSX"),
            "format_source","CONTRACT_M3_6","max_source_bytes",ImportLimits.MAX_SOURCE_BYTES,
            "max_data_rows",ImportLimits.MAX_DATA_ROWS,"max_source_columns",ImportLimits.MAX_COLUMNS,
            "max_sheets",ImportLimits.MAX_SHEETS,"max_header_row",ImportLimits.MAX_SOURCE_ROW_INDEX,
            "max_cell_chars",ImportLimits.MAX_CELL_CHARS,"resource_policy_version",ImportLimits.RESOURCE_POLICY_VERSION,
            "resource_limit_kind","TECHNICAL_CONTAINMENT","schema_version",AssetImportSchema.VERSION,
            "target_columns",AssetImportSchema.columns().stream().map(column->map("code",column.code().name(),
                "label",column.label(),"contract_always_required",column.contractAlwaysRequired())).toList(),
            "csv_encodings",List.of("UTF-8","UTF-16LE","UTF-16BE","GB18030"),
            "csv_delimiters",List.of(",",";","\t"),"sheet_index_base",0,"column_index_base",0,
            "row_number_base",1,"scan_status","UNSCANNED","production_approval","UNVERIFIED",
            "retention_until",null,"business_status","CONFIG_PENDING","can_import",false,
            "question_ids",List.of("Q20","Q31"));
    }

    /** Validates declaration only. Actual bytes are verified by the parser after immutable storage. */
    public void validate(String purpose,String filename,ImportFormat format,String declaredMedia,long size) {
        if(!PURPOSE.equals(purpose)||format==null||size<1||size>ImportLimits.MAX_SOURCE_BYTES
            ||filename==null||filename.isBlank()||filename.codePointCount(0,filename.length())>200
            ||filename.indexOf('/')>=0||filename.indexOf('\\')>=0
            ||filename.codePoints().anyMatch(Character::isISOControl))throw invalid();
        String extension=filename.toLowerCase(Locale.ROOT);
        if(format==ImportFormat.CSV&&!extension.endsWith(".csv")||format==ImportFormat.XLSX&&!extension.endsWith(".xlsx"))throw invalid();
        if(declaredMedia==null||declaredMedia.isBlank())return;
        String media=declaredMedia.split(";",2)[0].strip().toLowerCase(Locale.ROOT);
        Set<String> allowed=format==ImportFormat.CSV
            ?Set.of("text/csv","application/csv","text/plain","application/vnd.ms-excel","application/octet-stream")
            :Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/octet-stream");
        if(!allowed.contains(media))throw invalid();
    }
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","导入源仅接受匹配声明格式的 CSV/XLSX 文件，每份1至20971520 bytes；实际格式以解析结果为准");}
}
