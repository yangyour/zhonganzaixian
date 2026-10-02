package cn.mop.api;

import cn.mop.domain.assetimport.source.ImportFormat;
import cn.mop.domain.assetimport.source.ImportLimits;
import cn.mop.domain.assetimport.source.ParsedArtifact;
import cn.mop.domain.assetimport.source.ParsedArtifact.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated artifact-envelope checks, not a parser subprocess or storage integration. */
class ParsedArtifactStructureTest {
    private final List<SheetInfo> sheets = List.of(new SheetInfo(0,"synthetic",false));
    private final List<SourceColumn> columns = ParsedArtifact.columns(List.of("设备名称","设备名称","备注"));
    private SourceRow row(long logical, int source, int column) {
        return new SourceRow(logical,source,null,null,List.of(new SourceCell(column,CellKind.TEXT,"test","test",null,null)));
    }
    private ParsedArtifact parsed(List<SheetInfo> sheets, int selected, int header, List<SourceColumn> columns, List<SourceRow> rows) {
        return new ParsedArtifact(State.PARSED,ImportFormat.XLSX,sheets,selected,header,DateEpoch.WINDOWS_1900,columns,rows,"synthetic-parser",ImportLimits.RESOURCE_POLICY_VERSION);
    }
    @Test void repeatedHeaderLabelsAndSparseSourceCellsRemainValid() {
        var result=parsed(sheets,0,1,columns,List.of(row(1,2,2),row(2,7,0)));
        assertEquals(2,result.rows().size());
        assertEquals(result.columns().get(0).header(),result.columns().get(1).header());
    }
    @Test void sheetIdentityMustBeUniqueAndSelectionMustExist() {
        assertThrows(IllegalArgumentException.class,()->parsed(List.of(sheets.get(0),sheets.get(0)),0,1,columns,List.of(row(1,2,0))));
        assertThrows(IllegalArgumentException.class,()->parsed(sheets,1,1,columns,List.of(row(1,2,0))));
    }
    @Test void columnIdentityMustBeUniqueAndCoverEveryCell() {
        assertThrows(IllegalArgumentException.class,()->parsed(sheets,0,1,List.of(columns.get(0),columns.get(0)),List.of(row(1,2,0))));
        assertThrows(IllegalArgumentException.class,()->parsed(sheets,0,1,columns,List.of(row(1,2,3))));
    }
    @Test void sourceAndLogicalRowIdentitiesMustBothBeUnique() {
        assertThrows(IllegalArgumentException.class,()->parsed(sheets,0,1,columns,List.of(row(1,2,0),row(2,2,1))));
        assertThrows(IllegalArgumentException.class,()->parsed(sheets,0,1,columns,List.of(row(1,2,0),row(1,3,1))));
    }
    @Test void dataCannotIncludeHeaderOrPreHeaderRowsOrBeEmpty() {
        assertThrows(IllegalArgumentException.class,()->parsed(sheets,0,2,columns,List.of(row(1,2,0))));
        assertThrows(IllegalArgumentException.class,()->parsed(sheets,0,2,columns,List.of(row(1,1,0))));
        assertThrows(IllegalArgumentException.class,()->parsed(sheets,0,1,columns,List.of()));
        assertThrows(IllegalArgumentException.class,()->parsed(sheets,0,ImportLimits.MAX_SOURCE_ROW_INDEX+1,columns,List.of(row(1,2,0))));
    }
    @Test void selectionRequiredCannotClaimASelectedSheetOrHeader() {
        assertDoesNotThrow(()->new ParsedArtifact(State.SELECTION_REQUIRED,ImportFormat.XLSX,sheets,null,null,DateEpoch.WINDOWS_1900,List.of(),List.of(),"p","r"));
        assertThrows(IllegalArgumentException.class,()->new ParsedArtifact(State.SELECTION_REQUIRED,ImportFormat.XLSX,sheets,0,null,DateEpoch.WINDOWS_1900,List.of(),List.of(),"p","r"));
        assertThrows(IllegalArgumentException.class,()->new ParsedArtifact(State.SELECTION_REQUIRED,ImportFormat.XLSX,sheets,null,1,DateEpoch.WINDOWS_1900,List.of(),List.of(),"p","r"));
    }
    @Test void provenanceVersionsCannotBeBlank() {
        assertThrows(IllegalArgumentException.class,()->new ParsedArtifact(State.PARSED,ImportFormat.XLSX,sheets,0,1,DateEpoch.WINDOWS_1900,columns,List.of(row(1,2,0))," ","r"));
        assertThrows(IllegalArgumentException.class,()->new ParsedArtifact(State.PARSED,ImportFormat.XLSX,sheets,0,1,DateEpoch.WINDOWS_1900,columns,List.of(row(1,2,0)),"p"," "));
    }
}
