package cn.mop.assetexport;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetimport.AssetImportPrechecker.Column;
import cn.mop.domain.assetimport.source.AssetImportSchema;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

/** Bounded explicit-input encoding only. No database, object store, URL, notification, credentials or authorization grant. */
public final class AssetLedgerWorkbook {
    private AssetLedgerWorkbook() {}
    public static final int MAX_ROWS=1000, MAX_CELL_CODEPOINTS=2000, MAX_INPUT_UTF8_BYTES=8*1024*1024, MAX_OUTPUT_BYTES=16*1024*1024;
    public static final String SCHEMA_VERSION="contract-m3-7-standard15-plus-warranty-status-v1";
    public record Row(UUID tenantId,UUID assetId,Map<Column,String> cells,String warrantyStatus) {
        public Row {
            if(tenantId==null||assetId==null||cells==null||!cells.keySet().equals(EnumSet.allOf(Column.class)))throw invalid();
            EnumMap<Column,String> copy=new EnumMap<>(Column.class);
            for(Column column:Column.values()){text(cells.get(column));copy.put(column,cells.get(column));}
            text(warrantyStatus);cells=Collections.unmodifiableMap(copy);
        }
    }
    public static final class Encoded {
        private final byte[] bytes;private final int dataRows;private final String sha256;
        private Encoded(byte[] bytes,int dataRows){this.bytes=bytes.clone();this.dataRows=dataRows;try{sha256=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
        public byte[] bytes(){return bytes.clone();}
        public int dataRows(){return dataRows;}
        public int columns(){return 16;}
        public String sha256(){return sha256;}
        public boolean published(){return false;}
        public String selectionVerification(){return "CALLER_DECLARATION_ONLY";}
        public String authorizationVerification(){return "REQUIRED_AT_JOB_AND_DOWNLOAD";}
    }
    /** Caller must supply a complete authorized snapshot; true is a declaration, not verified permission. */
    public static Encoded encode(UUID tenantId,List<Row> rows,Boolean declaredComplete,Instant generatedAt)throws IOException {
        if(tenantId==null||rows==null||generatedAt==null||generatedAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||generatedAt.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();
        if(!Boolean.TRUE.equals(declaredComplete))throw new DomainException(422,"EXPORT_INPUT_INCOMPLETE","Incomplete or unverified selection cannot be encoded");
        if(rows.size()>MAX_ROWS)throw limit();
        if(rows.stream().anyMatch(Objects::isNull))throw invalid();
        var snapshot=List.copyOf(rows);var ids=new HashSet<UUID>();long bytes=0;
        for(Row row:snapshot){if(!tenantId.equals(row.tenantId()))throw new DomainException(404,"OBJECT_NOT_FOUND","Object not found in export scope");if(!ids.add(row.assetId()))throw invalid();for(String value:row.cells().values())bytes+=size(value);bytes+=size(row.warrantyStatus());if(bytes>MAX_INPUT_UTF8_BYTES)throw limit();}
        byte[] raw;
        try(var workbook=new XSSFWorkbook();var output=new LimitedOutput(MAX_OUTPUT_BYTES)){
            var sheet=workbook.createSheet("资产台账");var textStyle=workbook.createCellStyle();textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));textStyle.setWrapText(true);
            var headerStyle=workbook.createCellStyle();headerStyle.cloneStyleFrom(textStyle);var font=workbook.createFont();font.setBold(true);font.setColor(IndexedColors.WHITE.getIndex());headerStyle.setFont(font);headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var labels=new ArrayList<>(AssetImportSchema.columns().stream().map(AssetImportSchema.ColumnDefinition::label).toList());labels.add("维保状态");
            var header=sheet.createRow(0);header.setHeightInPoints(25);
            for(int c=0;c<labels.size();c++){var cell=header.createCell(c,CellType.STRING);cell.setCellValue(labels.get(c));cell.setCellStyle(headerStyle);sheet.setDefaultColumnStyle(c,textStyle);sheet.setColumnWidth(c,(c==14?42:22)*256);}
            int index=1;for(Row input:snapshot){var row=sheet.createRow(index++);int c=0;for(Column column:Column.values())literal(row.createCell(c++),input.cells().get(column),textStyle);literal(row.createCell(c),input.warrantyStatus(),textStyle);}
            sheet.createFreezePane(0,1);sheet.setAutoFilter(new CellRangeAddress(0,snapshot.size(),0,15));
            var properties=workbook.getProperties();properties.getCustomProperties().addProperty("mop.export_schema",SCHEMA_VERSION);properties.getCustomProperties().addProperty("mop.import_schema",AssetImportSchema.VERSION);properties.getCustomProperties().addProperty("mop.publication_status","ENCODED_NOT_PUBLISHED");properties.getCustomProperties().addProperty("mop.generated_at",generatedAt.toString());
            properties.getCoreProperties().setCreator("MOP");properties.getCoreProperties().setCreated(Optional.of(Date.from(generatedAt)));properties.getCoreProperties().setModified(Optional.of(Date.from(generatedAt)));properties.getCoreProperties().setDescription("Explicit input workbook encoding only; authorization, complete filter selection, storage, 15-minute download grants and notification are not verified by this library. Empty cells retain unknown values.");
            workbook.write(output);raw=output.toByteArray();
        }
        // Stable archive times for retries on the pinned toolchain, rather than fresh wall-clock ZIP metadata.
        try(var input=new ZipInputStream(new ByteArrayInputStream(raw));var output=new LimitedOutput(MAX_OUTPUT_BYTES);var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)){
            zip.setLevel(6);ZipEntry entry;while((entry=input.getNextEntry())!=null){var normalized=new ZipEntry(entry.getName());normalized.setTime(0);zip.putNextEntry(normalized);input.transferTo(zip);zip.closeEntry();}zip.finish();return new Encoded(output.toByteArray(),snapshot.size());
        }
    }
    private static void literal(org.apache.poi.ss.usermodel.Cell cell,String value,CellStyle style){cell.setCellStyle(style);if(value==null)cell.setBlank();else cell.setCellValue(value);}
    private static int size(String value){return value==null?0:value.getBytes(StandardCharsets.UTF_8).length;}
    private static void text(String value){if(value==null)return;if(value.codePointCount(0,value.length())>MAX_CELL_CODEPOINTS)throw limit();for(int offset=0;offset<value.length();){int cp=value.codePointAt(offset);if(cp<32&&cp!=9&&cp!=10&&cp!=13||cp==0xfffe||cp==0xffff||cp>=0xd800&&cp<=0xdfff)throw invalid();offset+=Character.charCount(cp);}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","Invalid explicit export cells or scope; input values are not logged");}
    private static DomainException limit(){return new DomainException(413,"EXPORT_ENCODING_LIMIT","Technical encoding resource limit reached; no rows were truncated");}
    private static final class LimitedOutput extends ByteArrayOutputStream {
        private final int maximum;LimitedOutput(int maximum){this.maximum=maximum;}
        private void check(int size){if(size<0||count>maximum-size)throw limit();}
        @Override public synchronized void write(int value){check(1);super.write(value);}
        @Override public synchronized void write(byte[] value,int offset,int size){check(size);super.write(value,offset,size);}
    }
}
