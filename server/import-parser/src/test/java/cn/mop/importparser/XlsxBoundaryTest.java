package cn.mop.importparser;

import cn.mop.domain.assetimport.source.*;
import cn.mop.domain.assetimport.source.ParsedArtifact.*;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class XlsxBoundaryTest {
    private static final String MAIN="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    @TempDir Path temporary;
    private final ImportFileParser parser=new ImportFileParser();
    private static ParseSelection selected(){return new ParseSelection(ImportFormat.XLSX,null,null,0,1);}
    private static ParseSelection inventory(){return new ParseSelection(ImportFormat.XLSX,null,null,null,null);}
    private Path workbook()throws Exception{
        Path file=temporary.resolve("MOP-FAKE.xlsx");try(XSSFWorkbook book=new XSSFWorkbook()){
            var sheet=book.createSheet("MOP-FAKE");sheet.createRow(0).createCell(0).setCellValue("名称");sheet.createRow(1).createCell(0).setCellValue("路由器");
            try(OutputStream output=Files.newOutputStream(file)){book.write(output);}
        }return file;
    }
    private Map<String,byte[]> parts(Path source)throws Exception{
        Map<String,byte[]> entries=new LinkedHashMap<>();try(ZipFile zip=new ZipFile(source.toFile())){var iterator=zip.entries();while(iterator.hasMoreElements()){var entry=iterator.nextElement();try(InputStream stream=zip.getInputStream(entry)){entries.put(entry.getName(),stream.readAllBytes());}}}return entries;
    }
    private Path write(Map<String,byte[]> entries)throws Exception{
        Path file=temporary.resolve("MOP-FAKE-mutated.xlsx");try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(file))){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}return file;
    }
    private Path sheet(String content)throws Exception{var entries=parts(workbook());entries.put("xl/worksheets/sheet1.xml",("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\""+MAIN+"\"><sheetData>"+content+"</sheetData></worksheet>").getBytes(StandardCharsets.UTF_8));return write(entries);}
    private void rejects(Path file,ParseFailure.Code code){assertEquals(code,assertThrows(ParseFailure.class,()->parser.parse(file,selected())).code());}
    @Test void inventoryReportsHiddenAndVeryHiddenSheetsWithoutClaimingRowsParsed()throws Exception{
        Path file=temporary.resolve("MOP-FAKE-hidden.xlsx");try(XSSFWorkbook book=new XSSFWorkbook()){
            book.createSheet("visible");book.createSheet("hidden");book.createSheet("veryHidden");book.setSheetVisibility(1,SheetVisibility.HIDDEN);book.setSheetVisibility(2,SheetVisibility.VERY_HIDDEN);
            try(OutputStream output=Files.newOutputStream(file)){book.write(output);}
        }
        var result=parser.parse(file,inventory());assertEquals(State.SELECTION_REQUIRED,result.state());assertEquals(List.of(false,true,true),result.sheets().stream().map(SheetInfo::hidden).toList());assertTrue(result.rows().isEmpty());assertNull(result.selectedSheetIndex());assertNull(result.headerRow());
    }
    @Test void sparseColumnsAndRichInlineTextRetainTheirActualPositions()throws Exception{
        var result=parser.parse(sheet("<row r=\"1\"><c r=\"C1\" t=\"inlineStr\"><is><r><t>设备</t></r><r><t>名称</t></r></is></c><c r=\"E1\" t=\"inlineStr\"><is><t>设备名称</t></is></c></row><row r=\"9\"><c r=\"E9\" t=\"inlineStr\"><is><r><t>MOP-</t></r><r><t>FAKE</t></r></is></c></row>"),selected());
        assertEquals(5,result.columns().size());assertNull(result.columns().get(0).header());assertEquals("设备名称",result.columns().get(2).header());assertEquals("设备名称",result.columns().get(4).header());
        var row=result.rows().get(0);assertEquals(9,row.sourceRowNumber());assertNull(row.physicalStartLine());assertEquals(4,row.cells().get(0).columnIndex());assertEquals("MOP-FAKE",row.cells().get(0).lexical());assertNull(row.cells().get(0).styleIndex());
    }
    @Test void numericDateSerialPreservesStyleAndUsesWorkbookEpochOnlyForDisplay()throws Exception{
        Path file=temporary.resolve("MOP-FAKE-dates.xlsx");try(XSSFWorkbook book=new XSSFWorkbook()){
            book.getCTWorkbook().getWorkbookPr().setDate1904(true);var style=book.createCellStyle();style.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd"));
            var sheet=book.createSheet("MOP-FAKE");sheet.createRow(0).createCell(0).setCellValue("采购日期");var cell=sheet.createRow(1).createCell(0);cell.setCellValue(1);cell.setCellStyle(style);try(OutputStream output=Files.newOutputStream(file)){book.write(output);}
        }
        var result=parser.parse(file,selected());var cell=result.rows().get(0).cells().get(0);assertEquals(DateEpoch.MAC_1904,result.dateEpoch());assertEquals(CellKind.NUMERIC,cell.kind());assertEquals("1.0",cell.lexical());assertEquals("1904-01-02",cell.display());assertEquals("yyyy-mm-dd",cell.formatCode());assertNotNull(cell.styleIndex());
        var direct=parser.parse(sheet("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>采购日期</t></is></c></row><row r=\"2\"><c r=\"A2\" t=\"d\"><v>2026-10-02T00:00:00Z</v></c></row>"),selected()).rows().get(0).cells().get(0);assertEquals(CellKind.ISO_DATE,direct.kind());assertEquals("2026-10-02T00:00:00Z",direct.lexical());
    }
    @Test void formulaAfterFourMegabytesAndOnUnselectedSheetIsRejected()throws Exception{
        rejects(sheet("<!--"+"x".repeat(4_200_000)+"--><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>名称</t></is></c></row><row r=\"2\"><c r=\"A2\"><f>1+1</f><v>2</v></c></row>"),ParseFailure.Code.FORMULA_NOT_ALLOWED);
        var entries=parts(workbook());entries.put("xl/worksheets/unused.xml",("<worksheet xmlns=\""+MAIN+"\"><sheetData><row r=\"1\"><c r=\"A1\"><f>1+1</f><v>2</v></c></row></sheetData></worksheet>").getBytes(StandardCharsets.UTF_8));
        Path file=write(entries);assertEquals(ParseFailure.Code.FORMULA_NOT_ALLOWED,assertThrows(ParseFailure.class,()->parser.parse(file,inventory())).code());
    }
    @Test void externalRelationshipsAndMacroContentTypesFailClosed()throws Exception{
        var entries=parts(workbook());entries.put("xl/worksheets/_rels/sheet1.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"hyperlink\" Target=\"https://invalid.example/MOP-FAKE\" TargetMode=\"External\"/></Relationships>".getBytes(StandardCharsets.UTF_8));rejects(write(entries),ParseFailure.Code.EXTERNAL_LINK_NOT_ALLOWED);
        entries=parts(workbook());entries.put("xl/vbaProject.bin",new byte[]{1,2,3});rejects(write(entries),ParseFailure.Code.MACRO_NOT_ALLOWED);
        entries=parts(workbook());String types=new String(entries.get("[Content_Types].xml"),StandardCharsets.UTF_8).replace("spreadsheetml.sheet.main+xml","ms-excel.sheet.macroEnabled.main+xml");entries.put("[Content_Types].xml",types.getBytes(StandardCharsets.UTF_8));rejects(write(entries),ParseFailure.Code.MACRO_NOT_ALLOWED);
    }
    @Test void entitiesDtdAndDeepXmlNeverExpandOrAccessExternalResources()throws Exception{
        var entries=parts(workbook());entries.put("xl/worksheets/sheet1.xml",("<!DOCTYPE worksheet [<!ENTITY secret SYSTEM \"file:///MOP-FAKE-private-secret\">]><worksheet xmlns=\""+MAIN+"\">&secret;</worksheet>").getBytes(StandardCharsets.UTF_8));rejects(write(entries),ParseFailure.Code.INVALID_XLSX);
        rejects(sheet("<x>".repeat(65)+"MOP-FAKE"+"</x>".repeat(65)),ParseFailure.Code.RESOURCE_LIMIT);
    }
    @Test void zipPartPathsDuplicateEntriesEncryptionAndEntryCountAreRejected()throws Exception{
        for(String name:List.of("../escape.xml","/absolute.xml","xl\\escape.xml","xl/%2e%2e/escape.xml")){var entries=parts(workbook());entries.put(name,new byte[]{1});rejects(write(entries),ParseFailure.Code.INVALID_XLSX);}
        var entries=parts(workbook());Path duplicate=temporary.resolve("MOP-FAKE-duplicate.xlsx");try(ZipArchiveOutputStream zip=new ZipArchiveOutputStream(duplicate.toFile())){for(var entry:entries.entrySet()){zip.putArchiveEntry(new ZipArchiveEntry(entry.getKey()));zip.write(entry.getValue());zip.closeArchiveEntry();}zip.putArchiveEntry(new ZipArchiveEntry("xl/workbook.xml"));zip.write(entries.get("xl/workbook.xml"));zip.closeArchiveEntry();}rejects(duplicate,ParseFailure.Code.INVALID_XLSX);
        entries=parts(workbook());for(int index=0;index<129;index++)entries.put("MOP-FAKE/part"+index+".bin",new byte[]{1});rejects(write(entries),ParseFailure.Code.RESOURCE_LIMIT);
        Path encrypted=temporary.resolve("MOP-FAKE-encrypted.xlsx");Files.write(encrypted,new byte[]{(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1});rejects(encrypted,ParseFailure.Code.ENCRYPTED_FILE_NOT_ALLOWED);
    }
    @Test void expandedLimitIncludesNonXmlAndAllEntries()throws Exception{
        var entries=parts(workbook());byte[] repeated=new byte[17*1024*1024];byte[] noise=new byte[2048];Random random=new Random(42);for(int offset=0;offset<repeated.length;offset+=32_768){random.nextBytes(noise);System.arraycopy(noise,0,repeated,offset,noise.length);}
        for(int index=0;index<4;index++)entries.put("MOP-FAKE/content"+index+".bin",repeated);
        Path file=write(entries);assertTrue(Files.size(file)<ImportLimits.MAX_SOURCE_BYTES);rejects(file,ParseFailure.Code.RESOURCE_LIMIT);
    }
    @Test void rowColumnAndSheetLimitsRejectRatherThanTruncate()throws Exception{
        String header="<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>名称</t></is></c></row>";StringBuilder rows=new StringBuilder(header);
        for(int index=2;index<=501;index++)rows.append("<row r=\"").append(index).append("\"><c r=\"A").append(index).append("\"><v>").append(index).append("</v></c></row>");assertEquals(500,parser.parse(sheet(rows.toString()),selected()).rows().size());
        rows.append("<row r=\"502\"><c r=\"A502\"><v>502</v></c></row>");rejects(sheet(rows.toString()),ParseFailure.Code.TOO_MANY_ROWS);
        rejects(sheet(header+"<row r=\"2\"><c r=\"BM2\"><v>1</v></c></row>"),ParseFailure.Code.RESOURCE_LIMIT);
        Path file=temporary.resolve("MOP-FAKE-sheets.xlsx");try(XSSFWorkbook book=new XSSFWorkbook()){for(int index=0;index<33;index++)book.createSheet("MOP-FAKE-"+index);try(OutputStream output=Files.newOutputStream(file)){book.write(output);}}assertEquals(ParseFailure.Code.RESOURCE_LIMIT,assertThrows(ParseFailure.class,()->parser.parse(file,inventory())).code());
    }
    @Test void malformedCellLocationsAndInvalidSharedStringStyleOrNumericValuesAreRejected()throws Exception{
        String header="<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>名称</t></is></c></row>";
        for(String cell:List.of("<c r=\"A3\"><v>1</v></c>","<c r=\"A2\" s=\"999\"><v>1</v></c>","<c r=\"A2\" t=\"s\"><v>9999</v></c>","<c r=\"A2\"><v>NaN</v></c>","<c r=\"A2\"><v>1</v><v>2</v></c>","<c r=\"A2\" t=\"inlineStr\"><v>1</v><is><t>MOP-FAKE</t></is></c>","<c r=\"A2\"><v>1</v></c><c r=\"A2\"><v>2</v></c>"))rejects(sheet(header+"<row r=\"2\">"+cell+"</row>"),ParseFailure.Code.INVALID_XLSX);
    }
}
