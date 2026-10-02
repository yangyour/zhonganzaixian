package cn.mop.importparser;

import cn.mop.domain.assetimport.source.*;
import cn.mop.domain.assetimport.source.ParsedArtifact.*;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImportFileParserTest {
    private final ImportFileParser parser=new ImportFileParser();
    private static Path source(String suffix,byte[] bytes)throws Exception{Path path=Files.createTempFile("MOP-FAKE-import-",suffix);Files.write(path,bytes);return path;}
    private static ParseSelection csv(String encoding,String delimiter){return new ParseSelection(ImportFormat.CSV,encoding,delimiter,null,1);}
    @Test void csvPreservesDuplicateHeadersPhysicalLinesAndFormulaLookingText()throws Exception{
        Path path=source(".csv","设备名称,设备名称,备注\nrouter,router,=SUM(A1)\n下一行,x,MOP-FAKE\n".getBytes(StandardCharsets.UTF_8));
        try{ParsedArtifact artifact=parser.parse(path,csv("UTF-8",","));assertEquals(ParsedArtifact.State.PARSED,artifact.state());assertEquals(List.of(0,1,2),artifact.columns().stream().map(ParsedArtifact.SourceColumn::index).toList());assertEquals(List.of("设备名称","设备名称","备注"),artifact.columns().stream().map(ParsedArtifact.SourceColumn::header).toList());assertEquals("=SUM(A1)",artifact.rows().get(0).cells().get(2).lexical());assertEquals(2,artifact.rows().get(0).logicalRecordNumber());assertEquals(2,artifact.rows().get(0).sourceRowNumber());assertEquals(2L,artifact.rows().get(0).physicalStartLine());assertEquals(2L,artifact.rows().get(0).physicalEndLine());assertEquals(ParsedArtifact.CellKind.TEXT,artifact.rows().get(0).cells().get(2).kind());}
        finally{Files.deleteIfExists(path);}
    }
    @Test void csvReadsUtf8BomAndRejectsMismatchedOrUnboundedRows()throws Exception{
        byte[] bom="\ufeff设备名称,备注\nr,MOP\n".getBytes(StandardCharsets.UTF_8);Path path=source(".csv",bom);
        try{assertEquals(ParsedArtifact.State.PARSED,parser.parse(path,csv("UTF-8",",")).state());assertThrows(ParseFailure.class,()->parser.parse(path,csv("GB18030",",")));StringBuilder value=new StringBuilder("设备名称,备注\n");for(int i=0;i<501;i++)value.append("r").append(i).append(",M\n");Files.writeString(path,value.toString());ParseFailure failure=assertThrows(ParseFailure.class,()->parser.parse(path,csv("UTF-8",",")));assertEquals(ParseFailure.Code.TOO_MANY_ROWS,failure.code());}
        finally{Files.deleteIfExists(path);}
    }
    @Test void xlsxRequiresExplicitSheetAndHeaderAndPreservesStyleDateAndRows()throws Exception{
        Path path=Files.createTempFile("MOP-FAKE-import-", ".xlsx");try(XSSFWorkbook workbook=new XSSFWorkbook()){
            XSSFSheet first=workbook.createSheet("One");first.createRow(0).createCell(0).setCellValue("设备名称");first.getRow(0).createCell(1).setCellValue("备注");first.createRow(1).createCell(0).setCellValue("router");first.getRow(1).createCell(1).setCellValue("MOP-FAKE");XSSFSheet second=workbook.createSheet("Two");second.createRow(0).createCell(0).setCellValue("ignored");try(var output=Files.newOutputStream(path)){workbook.write(output);}}
        try{ParsedArtifact inventory=parser.parse(path,new ParseSelection(ImportFormat.XLSX,null,null,null,null));assertEquals(ParsedArtifact.State.SELECTION_REQUIRED,inventory.state());assertEquals(List.of("One","Two"),inventory.sheets().stream().map(ParsedArtifact.SheetInfo::name).toList());ParsedArtifact parsed=parser.parse(path,new ParseSelection(ImportFormat.XLSX,null,null,0,1));assertEquals(ParsedArtifact.State.PARSED,parsed.state());assertEquals(1,parsed.rows().size());assertEquals(ParsedArtifact.DateEpoch.WINDOWS_1900,parsed.dateEpoch());assertEquals(0,parsed.rows().get(0).cells().get(0).columnIndex());}
        finally{Files.deleteIfExists(path);}
    }
    @Test void xlsxFormulaIsRejectedWithoutReadingCachedValue()throws Exception{
        Path path=Files.createTempFile("MOP-FAKE-import-", ".xlsx");try(XSSFWorkbook workbook=new XSSFWorkbook()){var sheet=workbook.createSheet("One");sheet.createRow(0).createCell(0).setCellValue("备注");sheet.createRow(1).createCell(0).setCellFormula("1+1");try(var output=Files.newOutputStream(path)){workbook.write(output);}}
        try{ParseFailure failure=assertThrows(ParseFailure.class,()->parser.parse(path,new ParseSelection(ImportFormat.XLSX,null,null,0,1)));assertEquals(ParseFailure.Code.FORMULA_NOT_ALLOWED,failure.code());}
        finally{Files.deleteIfExists(path);}
    }
    @Test void mappingRequiresAllFifteenColumnsAndExplicitUnusedSources(){
        EnumMap<cn.mop.domain.assetimport.AssetImportPrechecker.Column,Integer> map=new EnumMap<>(cn.mop.domain.assetimport.AssetImportPrechecker.Column.class);for(var column:cn.mop.domain.assetimport.AssetImportPrechecker.Column.values())map.put(column,column.ordinal());String hash="a".repeat(64);var request=new MappingRequest(1,hash,map,Set.of());assertThrows(IllegalArgumentException.class,()->request.validateAgainst(new ParsedArtifact(ParsedArtifact.State.PARSED,ImportFormat.CSV,List.of(new SheetInfo(0,"CSV",false)),0,1,DateEpoch.NOT_APPLICABLE,ParsedArtifact.columns(List.of("a")),List.of(new SourceRow(1,2,2L,2L,List.of(new SourceCell(0,CellKind.TEXT,"x","x",null,null)))),"p","r")));}
}
