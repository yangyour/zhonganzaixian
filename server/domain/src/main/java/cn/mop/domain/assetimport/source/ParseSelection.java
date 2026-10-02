package cn.mop.domain.assetimport.source;

import java.util.Set;

/** Sheet/column indices start at 0; header and source row/record/line numbers start at 1. */
public record ParseSelection(ImportFormat declaredFormat,String encoding,String delimiter,Integer sheetIndex,Integer headerRow) {
    public ParseSelection {
        if(declaredFormat==null)throw new IllegalArgumentException("A declared format is required");
        if(headerRow!=null&&(headerRow<1||headerRow>ImportLimits.MAX_SOURCE_ROW_INDEX))throw new IllegalArgumentException("Invalid header row");
        if(declaredFormat==ImportFormat.CSV){
            if(encoding==null||!Set.of("UTF-8","UTF-16LE","UTF-16BE","GB18030").contains(encoding)||delimiter==null||!Set.of(",",";","\t").contains(delimiter)||sheetIndex!=null||headerRow==null)
                throw new IllegalArgumentException("CSV requires explicit encoding, delimiter and header row");
        }else if(encoding!=null||delimiter!=null||sheetIndex!=null&&(sheetIndex<0||sheetIndex>=ImportLimits.MAX_SHEETS))throw new IllegalArgumentException("Invalid XLSX selection");
    }
}
