package cn.mop.domain.assetimport.source;

import cn.mop.domain.assetimport.AssetImportPrechecker.Column;
import java.util.*;

/** Immutable raw parse result. No date policy, business normalization, quota or asset write is represented. */
public record ParsedArtifact(State state,ImportFormat detectedFormat,List<SheetInfo> sheets,Integer selectedSheetIndex,Integer headerRow,
                             DateEpoch dateEpoch,List<SourceColumn> columns,List<SourceRow> rows,String parserVersion,String resourcePolicyVersion) {
    public enum State { PARSED,SELECTION_REQUIRED }
    public enum DateEpoch { NOT_APPLICABLE,WINDOWS_1900,MAC_1904 }
    public enum CellKind { TEXT,NUMERIC,BOOLEAN,ERROR,ISO_DATE,BLANK }
    public record SheetInfo(int index,String name,boolean hidden){
        public SheetInfo{if(index<0||index>=ImportLimits.MAX_SHEETS||name==null||name.length()>255)throw new IllegalArgumentException("Invalid sheet metadata");}
    }
    public record SourceColumn(int index,String header,List<Column> targetSuggestions){
        public SourceColumn{if(index<0||index>=ImportLimits.MAX_COLUMNS||header!=null&&header.length()>ImportLimits.MAX_CELL_CHARS)throw new IllegalArgumentException("Invalid column metadata");targetSuggestions=List.copyOf(targetSuggestions);}
    }
    public record SourceCell(int columnIndex,CellKind kind,String lexical,String display,Integer styleIndex,String formatCode){
        public SourceCell{if(columnIndex<0||columnIndex>=ImportLimits.MAX_COLUMNS||kind==null||lexical!=null&&lexical.length()>ImportLimits.MAX_CELL_CHARS||display!=null&&display.length()>ImportLimits.MAX_CELL_CHARS||styleIndex!=null&&styleIndex<0||formatCode!=null&&formatCode.length()>ImportLimits.MAX_CELL_CHARS)throw new IllegalArgumentException("Invalid cell metadata");}
    }
    public record SourceRow(long logicalRecordNumber,int sourceRowNumber,Long physicalStartLine,Long physicalEndLine,List<SourceCell> cells){
        public SourceRow{if(logicalRecordNumber<1||sourceRowNumber<1||sourceRowNumber>ImportLimits.MAX_SOURCE_ROW_INDEX||physicalStartLine!=null&&physicalStartLine<1||physicalEndLine!=null&&(physicalEndLine<1||physicalStartLine!=null&&physicalEndLine<physicalStartLine))throw new IllegalArgumentException("Invalid row metadata");cells=List.copyOf(cells);Set<Integer> indices=new HashSet<>();for(SourceCell cell:cells)if(!indices.add(cell.columnIndex()))throw new IllegalArgumentException("Duplicate cell position");}
    }
    public ParsedArtifact{
        Objects.requireNonNull(state);Objects.requireNonNull(detectedFormat);Objects.requireNonNull(dateEpoch);sheets=List.copyOf(sheets);columns=List.copyOf(columns);rows=List.copyOf(rows);
        if(sheets.isEmpty()||sheets.size()>ImportLimits.MAX_SHEETS||columns.size()>ImportLimits.MAX_COLUMNS||rows.size()>ImportLimits.MAX_DATA_ROWS||parserVersion==null||resourcePolicyVersion==null)throw new IllegalArgumentException("Invalid parsed artifact");
        if(state==State.SELECTION_REQUIRED&&(!columns.isEmpty()||!rows.isEmpty()))throw new IllegalArgumentException("Selection is required before rows are parsed");
        if(state==State.PARSED&&(selectedSheetIndex==null||headerRow==null||headerRow<1||columns.isEmpty()))throw new IllegalArgumentException("Parsed selection is incomplete");
    }
    public static List<SourceColumn> columns(List<String> headers){
        List<SourceColumn> result=new ArrayList<>();for(int index=0;index<headers.size();index++){String header=headers.get(index);List<Column> matches=Arrays.stream(Column.values()).filter(column->column.label().equals(header)).toList();result.add(new SourceColumn(index,header,matches));}return List.copyOf(result);
    }
}
