package cn.mop.importparser;

import cn.mop.domain.assetimport.source.AssetImportSchema;
import cn.mop.domain.assetimport.source.ImportFormat;
import cn.mop.domain.assetimport.source.ParseSelection;
import cn.mop.domain.assetimport.source.ParseFailure;
import cn.mop.domain.assetimport.source.ParsedArtifact;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AssetImportTemplateTest {
    @TempDir Path directory;

    @Test void exactlyFifteenContractLabelsWithNoSampleRowsOrGuessedRules() throws Exception {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(AssetImportTemplate.create()))) {
            assertEquals(1, workbook.getNumberOfSheets());
            assertFalse(workbook.isSheetHidden(0));
            var sheet = workbook.getSheetAt(0);
            assertEquals("资产导入", sheet.getSheetName());
            assertEquals(1, sheet.getPhysicalNumberOfRows());
            assertEquals(15, sheet.getRow(0).getPhysicalNumberOfCells());
            for (int index = 0; index < 15; index++) {
                var cell = sheet.getRow(0).getCell(index);
                assertEquals(CellType.STRING, cell.getCellType());
                assertEquals(AssetImportSchema.columns().get(index).label(), cell.getStringCellValue());
                assertEquals("@", sheet.getColumnStyle(index).getDataFormatString());
            }
            assertTrue(sheet.getDataValidations().isEmpty()); // No invented device/collection dictionaries.
            assertTrue(workbook.getExternalLinksTable().isEmpty());
            assertFalse(workbook.isMacroEnabled());
            assertEquals("CONFIG_PENDING", workbook.getProperties().getCustomProperties().getProperty("mop.business_status").getLpwstr());
        }
    }

    @Test void realParserListsTheTemplateButRejectsEmptyDataUntilRowsAreFilled() throws Exception {
        Path path = directory.resolve("empty.xlsx"); Files.write(path, AssetImportTemplate.create());
        var parser = new ImportFileParser();
        var inventory = parser.parse(path, new ParseSelection(ImportFormat.XLSX, null, null, null, null));
        assertEquals(ParsedArtifact.State.SELECTION_REQUIRED, inventory.state());
        assertEquals("资产导入", inventory.sheets().get(0).name());
        var failure = assertThrows(ParseFailure.class, () -> parser.parse(path, new ParseSelection(ImportFormat.XLSX, null, null, 0, 1)));
        assertEquals(ParseFailure.Code.EMPTY_DATA, failure.code());
    }

    @Test void textCellsPreserveLeadingZeroIdentifiersAndFormulaLookingRawText() throws Exception {
        Path path = directory.resolve("filled.xlsx");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(AssetImportTemplate.create()))) {
            var row = workbook.getSheetAt(0).createRow(1);
            row.createCell(5).setCellValue("000123");
            row.createCell(14).setCellValue("=1+1");
            try (var output = Files.newOutputStream(path)) { workbook.write(output); }
        }
        var parsed = new ImportFileParser().parse(path, new ParseSelection(ImportFormat.XLSX, null, null, 0, 1));
        assertEquals(AssetImportSchema.columns().stream().map(AssetImportSchema.ColumnDefinition::label).toList(),
            parsed.columns().stream().map(ParsedArtifact.SourceColumn::header).toList());
        // POI writes shared strings: lexical retains the original SST index, display resolves the text.
        var serial = parsed.rows().get(0).cells().stream().filter(cell -> cell.columnIndex() == 5).findFirst().orElseThrow();
        assertEquals("000123", serial.display()); assertEquals(ParsedArtifact.CellKind.TEXT, serial.kind());
        assertTrue(serial.lexical().matches("[0-9]+"));
        var note = parsed.rows().get(0).cells().stream().filter(cell -> cell.columnIndex() == 14).findFirst().orElseThrow();
        assertEquals(ParsedArtifact.CellKind.TEXT, note.kind()); assertEquals("=1+1", note.display());
    }

    @Test void cliRejectsOverwriteAndInvalidInvocation() throws Exception {
        Path path = directory.resolve("template.xlsx");
        AssetImportTemplate.main(new String[] { path.toString() });
        byte[] original = Files.readAllBytes(path);
        assertThrows(FileAlreadyExistsException.class, () -> AssetImportTemplate.main(new String[] { path.toString() }));
        assertArrayEquals(original, Files.readAllBytes(path));
        assertThrows(IllegalArgumentException.class, () -> AssetImportTemplate.main(new String[0]));
        assertThrows(IllegalArgumentException.class, () -> AssetImportTemplate.main(new String[] { directory.resolve("bad.csv").toString() }));
    }
    @Test void bundledDownloadResourceHasExactContractLabelsAndStillRequiresData()throws Exception{
        byte[] bytes;
        try(var input=getClass().getResourceAsStream("/asset-import-standard15.xlsx")){assertNotNull(input);bytes=input.readAllBytes();}
        assertEquals("8bf810106054cacd60e41e47848d06d677cf62622fa3c5109c8040c66088932c",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes))){
            assertEquals(1,workbook.getNumberOfSheets());assertFalse(workbook.isSheetHidden(0));assertFalse(workbook.isMacroEnabled());assertTrue(workbook.getExternalLinksTable().isEmpty());var sheet=workbook.getSheetAt(0);
            assertEquals(1,sheet.getPhysicalNumberOfRows());assertEquals(15,sheet.getRow(0).getPhysicalNumberOfCells());assertTrue(sheet.getDataValidations().isEmpty());
            for(int i=0;i<15;i++){assertEquals(AssetImportSchema.columns().get(i).label(),sheet.getRow(0).getCell(i).getStringCellValue());assertEquals("@",sheet.getColumnStyle(i).getDataFormatString());}
            assertEquals("CONFIG_PENDING",workbook.getProperties().getCustomProperties().getProperty("mop.business_status").getLpwstr());
        }
        Path path=directory.resolve("bundled-download.xlsx");Files.write(path,bytes);var parser=new ImportFileParser();
        assertEquals(ParsedArtifact.State.SELECTION_REQUIRED,parser.parse(path,new ParseSelection(ImportFormat.XLSX,null,null,null,null)).state());
        assertEquals(ParseFailure.Code.EMPTY_DATA,assertThrows(ParseFailure.class,()->parser.parse(path,new ParseSelection(ImportFormat.XLSX,null,null,0,1))).code());
    }
}
