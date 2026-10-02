package cn.mop.importparser;

import cn.mop.domain.assetimport.source.*;
import cn.mop.domain.assetimport.source.ParsedArtifact.*;
import org.apache.poi.openxml4j.opc.*;
import org.apache.poi.xssf.eventusermodel.*;
import org.apache.poi.xssf.model.*;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Read-only OOXML SAX parser. All parts are preflighted, including unselected sheets. */
final class XlsxSourceParser {
    private static final String MAIN="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private XlsxSourceParser(){}
    static ParsedArtifact parse(Path input,ParseSelection selection,ParseBudget budget)throws Exception{
        WorkbookMetadata metadata=preflight(input,budget);
        List<SheetInfo> sheets=metadata.sheets;
        DateEpoch epoch=metadata.epoch;
        if(selection.sheetIndex()==null||selection.headerRow()==null)
            return new ParsedArtifact(State.SELECTION_REQUIRED,ImportFormat.XLSX,sheets,null,null,epoch,List.of(),List.of(),ImportFileParser.VERSION,ImportLimits.RESOURCE_POLICY_VERSION);
        if(selection.sheetIndex()>=sheets.size())throw fail(ParseFailure.Code.SELECTION_OUT_OF_RANGE);
        try(OPCPackage packageFile=OPCPackage.open(input.toFile(),PackageAccess.READ)){
            budget.check();
            XSSFReader reader=new XSSFReader(packageFile);reader.setUseReadOnlySharedStringsTable(true);
            ReadOnlySharedStringsTable shared=new ReadOnlySharedStringsTable(packageFile,true);
            if(shared.getUniqueCount()>ImportLimits.MAX_SHARED_STRINGS)throw fail(ParseFailure.Code.RESOURCE_LIMIT);
            StylesTable styles=reader.getStylesTable();
            if(styles!=null&&styles.getNumCellStyles()>ImportLimits.MAX_STYLES)throw fail(ParseFailure.Code.RESOURCE_LIMIT);
            XSSFReader.SheetIterator iterator=reader.getSheetIterator();int index=0;
            while(iterator.hasNext()){
                budget.check();
                try(InputStream candidate=iterator.next()){
                    if(index++!=selection.sheetIndex())continue;
                    if(!iterator.getSheetName().equals(sheets.get(selection.sheetIndex()).name()))throw fail(ParseFailure.Code.INVALID_XLSX);
                    SheetHandler handler=new SheetHandler(selection.headerRow(),shared,styles,epoch,budget);
                    SafeXml.parse(candidate,handler,new SafeXml.Context(budget));
                    return handler.result(sheets,selection.sheetIndex());
                }
            }
            throw fail(ParseFailure.Code.SELECTION_OUT_OF_RANGE);
        }catch(org.apache.poi.EncryptedDocumentException encrypted){throw fail(ParseFailure.Code.ENCRYPTED_FILE_NOT_ALLOWED);}
    }

    private static WorkbookMetadata preflight(Path input,ParseBudget budget)throws Exception{
        byte[] signature;try(InputStream stream=Files.newInputStream(input)){signature=stream.readNBytes(8);}
        byte[] ole={(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1};
        if(Arrays.equals(signature,ole))throw fail(ParseFailure.Code.ENCRYPTED_FILE_NOT_ALLOWED);
        if(signature.length<4||signature[0]!='P'||signature[1]!='K'||signature[2]!=3||signature[3]!=4)throw fail(ParseFailure.Code.INVALID_XLSX);
        WorkbookMetadata metadata=new WorkbookMetadata();
        SafeXml.Context xmlBudget=new SafeXml.Context(budget);
        Set<String> names=new HashSet<>();long[] expanded={0};int count=0;
        try(ZipFile zip=new ZipFile(input.toFile())){
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()){
                budget.check();ZipEntry entry=entries.nextElement();
                if(++count>ImportLimits.MAX_ZIP_ENTRIES)throw fail(ParseFailure.Code.RESOURCE_LIMIT);
                String name=entry.getName(),lower=name.toLowerCase(Locale.ROOT);
                validatePartName(name);
                if(!names.add(lower))throw fail(ParseFailure.Code.INVALID_XLSX);
                if(lower.contains("vbaproject")||lower.endsWith(".vba")||lower.contains("/macrosheets/"))throw fail(ParseFailure.Code.MACRO_NOT_ALLOWED);
                if(lower.startsWith("xl/externallinks/")||lower.contains("externallink"))throw fail(ParseFailure.Code.EXTERNAL_LINK_NOT_ALLOWED);
                if(entry.isDirectory())continue;
                if(entry.getSize()>ImportLimits.MAX_ZIP_ENTRY_BYTES||entry.getSize()<0)throw fail(ParseFailure.Code.RESOURCE_LIMIT);
                long[] entryBytes={0};CRC32 checksum=new CRC32();
                try(InputStream stream=new FilterInputStream(zip.getInputStream(entry)){
                    private void consumed(int bytes){budget.check();if(bytes>0&&(entryBytes[0]+=bytes)>ImportLimits.MAX_ZIP_ENTRY_BYTES)throw fail(ParseFailure.Code.RESOURCE_LIMIT);if(bytes>0&&(expanded[0]+=bytes)>ImportLimits.MAX_EXPANDED_BYTES)throw fail(ParseFailure.Code.RESOURCE_LIMIT);}
                    @Override public int read()throws IOException{int result=in.read();consumed(result<0?0:1);if(result>=0)checksum.update(result);return result;}
                    @Override public int read(byte[] target,int start,int length)throws IOException{int result=in.read(target,start,length);consumed(result);if(result>0)checksum.update(target,start,result);return result;}
                }){
                    if(lower.endsWith(".xml")||lower.endsWith(".rels")){
                        SecurityHandler security=new SecurityHandler(lower,metadata);
                        SafeXml.parse(stream,security,xmlBudget);
                    }else stream.transferTo(OutputStream.nullOutputStream());
                }
                if(entryBytes[0]!=entry.getSize()||checksum.getValue()!=entry.getCrc())throw fail(ParseFailure.Code.INVALID_XLSX);
                if(entryBytes[0]>1024&&entry.getCompressedSize()*100L<entryBytes[0])throw fail(ParseFailure.Code.RESOURCE_LIMIT);
            }
        }catch(ZipException invalid){throw fail(ParseFailure.Code.INVALID_XLSX);}
        if(!names.contains("[content_types].xml")||!names.contains("xl/workbook.xml")||metadata.sheets.isEmpty())throw fail(ParseFailure.Code.INVALID_XLSX);
        return metadata;
    }
    private static void validatePartName(String name){
        if(name==null||name.isEmpty()||name.length()>1024||name.startsWith("/")||name.indexOf('\\')>=0||name.indexOf(':')>=0||name.indexOf('\0')>=0)throw fail(ParseFailure.Code.INVALID_XLSX);
        String part=name.endsWith("/")?name.substring(0,name.length()-1):name;
        for(String segment:part.split("/",-1))if(segment.isEmpty()||segment.equals(".")||segment.equals("..")||segment.contains("%"))throw fail(ParseFailure.Code.INVALID_XLSX);
    }
    private static ParseFailure fail(ParseFailure.Code code){return new ParseFailure(code);}
    private static int positiveInt(String value){try{int result=Integer.parseInt(value);if(result<1)throw new NumberFormatException();return result;}catch(RuntimeException invalid){throw fail(ParseFailure.Code.INVALID_XLSX);}}
    private static int nonnegativeInt(String value){try{int result=Integer.parseInt(value);if(result<0)throw new NumberFormatException();return result;}catch(RuntimeException invalid){throw fail(ParseFailure.Code.INVALID_XLSX);}}
    private static void cellText(String value){if(value.length()>ImportLimits.MAX_CELL_CHARS||value.codePoints().anyMatch(code->Character.isISOControl(code)&&code!='\t'&&code!='\r'&&code!='\n'))throw fail(ParseFailure.Code.RESOURCE_LIMIT);}
    private static final class WorkbookMetadata {
        DateEpoch epoch=DateEpoch.WINDOWS_1900;
        final List<SheetInfo> sheets=new ArrayList<>();
    }
    private static final class SecurityHandler extends DefaultHandler {
        private final String part;private final WorkbookMetadata metadata;
        private int sharedStrings,styles,stringChars;private boolean inSharedString;
        SecurityHandler(String part,WorkbookMetadata metadata){this.part=part;this.metadata=metadata;}
        @Override public void startElement(String uri,String name,String qualified,Attributes atts){
            if(part.endsWith(".rels")&&name.equals("Relationship")){
                String mode=atts.getValue("TargetMode"),type=atts.getValue("Type"),target=atts.getValue("Target");
                if("External".equalsIgnoreCase(mode)||type!=null&&(type.toLowerCase(Locale.ROOT).contains("externallink")||type.toLowerCase(Locale.ROOT).contains("vbaproject")))throw fail(type!=null&&type.toLowerCase(Locale.ROOT).contains("vbaproject")?ParseFailure.Code.MACRO_NOT_ALLOWED:ParseFailure.Code.EXTERNAL_LINK_NOT_ALLOWED);
                if(target!=null&&(target.startsWith("//")||target.indexOf('\\')>=0||target.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")))throw fail(ParseFailure.Code.EXTERNAL_LINK_NOT_ALLOWED);
            }
            if(part.equals("[content_types].xml")){String content=atts.getValue("ContentType");if(content!=null&&(content.toLowerCase(Locale.ROOT).contains("macroenabled")||content.toLowerCase(Locale.ROOT).contains("vbaproject")))throw fail(ParseFailure.Code.MACRO_NOT_ALLOWED);}
            if(part.startsWith("xl/worksheets/")&&name.equals("f"))throw fail(ParseFailure.Code.FORMULA_NOT_ALLOWED);
            if(part.equals("xl/workbook.xml")&&Set.of("externalReferences","externalReference").contains(name))throw fail(ParseFailure.Code.EXTERNAL_LINK_NOT_ALLOWED);
            if(!MAIN.equals(uri))return;
            if(part.equals("xl/workbook.xml")){
                if(name.equals("workbookPr")){String epoch=atts.getValue("date1904");if(epoch!=null&&!Set.of("0","1","false","true").contains(epoch))throw fail(ParseFailure.Code.INVALID_XLSX);metadata.epoch="1".equals(epoch)||"true".equals(epoch)?DateEpoch.MAC_1904:DateEpoch.WINDOWS_1900;}
                if(name.equals("sheet")){
                    if(metadata.sheets.size()>=ImportLimits.MAX_SHEETS)throw fail(ParseFailure.Code.RESOURCE_LIMIT);
                    String state=atts.getValue("state"),sheetName=atts.getValue("name");if(state!=null&&!Set.of("visible","hidden","veryHidden").contains(state))throw fail(ParseFailure.Code.INVALID_XLSX);
                    if(sheetName==null||sheetName.isEmpty()||metadata.sheets.stream().anyMatch(sheet->sheet.name().equalsIgnoreCase(sheetName)))throw fail(ParseFailure.Code.INVALID_XLSX);
                    metadata.sheets.add(new SheetInfo(metadata.sheets.size(),sheetName,"hidden".equals(state)||"veryHidden".equals(state)));
                }
            }
            if(part.equals("xl/sharedstrings.xml")&&name.equals("si")){if(++sharedStrings>ImportLimits.MAX_SHARED_STRINGS)throw fail(ParseFailure.Code.RESOURCE_LIMIT);inSharedString=true;stringChars=0;}
            if(part.equals("xl/styles.xml")&&name.equals("xf")&&++styles>ImportLimits.MAX_STYLES*2)throw fail(ParseFailure.Code.RESOURCE_LIMIT);
        }
        @Override public void characters(char[] chars,int start,int count){if(inSharedString&&(stringChars+=count)>ImportLimits.MAX_CELL_CHARS)throw fail(ParseFailure.Code.RESOURCE_LIMIT);}
        @Override public void endElement(String uri,String name,String qualified){if(MAIN.equals(uri)&&name.equals("si"))inSharedString=false;}
    }

    private static final class SheetHandler extends DefaultHandler {
        private final int headerRow;private final ReadOnlySharedStringsTable shared;private final StylesTable styles;private final DateEpoch epoch;private final ParseBudget budget;
        private final DataFormatter formatter=new DataFormatter(Locale.ROOT);
        private final List<String> headers=new ArrayList<>();private final List<SourceRow> rows=new ArrayList<>();private final List<SourceCell> cells=new ArrayList<>();
        private final StringBuilder value=new StringBuilder();private int rowNumber,lastRow,currentColumn=-1,lastColumn=-1,currentStyle=-1,width;private String type;private boolean inValue,inCell,inInline,valueSeen,inlineSeen,rootSeen,headerSeen;
        SheetHandler(int headerRow,ReadOnlySharedStringsTable shared,StylesTable styles,DateEpoch epoch,ParseBudget budget){this.headerRow=headerRow;this.shared=shared;this.styles=styles;this.epoch=epoch;this.budget=budget;}
        @Override public void startElement(String uri,String name,String qualified,Attributes atts){
            if(!MAIN.equals(uri))return;
            if(name.equals("worksheet"))rootSeen=true;
            if(name.equals("row")){
                if(rowNumber!=0)throw fail(ParseFailure.Code.INVALID_XLSX);
                rowNumber=atts.getValue("r")==null?lastRow+1:positiveInt(atts.getValue("r"));
                if(rowNumber<=lastRow)throw fail(ParseFailure.Code.INVALID_XLSX);
                if(rowNumber>ImportLimits.MAX_SOURCE_ROW_INDEX)throw fail(ParseFailure.Code.RESOURCE_LIMIT);
                lastRow=rowNumber;lastColumn=-1;cells.clear();
            }else if(name.equals("c")){
                if(rowNumber==0||inCell)throw fail(ParseFailure.Code.INVALID_XLSX);inCell=true;
                currentColumn=column(atts.getValue("r"),rowNumber,lastColumn+1);if(currentColumn<=lastColumn)throw fail(ParseFailure.Code.INVALID_XLSX);
                if(currentColumn>=ImportLimits.MAX_COLUMNS)throw fail(ParseFailure.Code.RESOURCE_LIMIT);
                lastColumn=currentColumn;width=Math.max(width,currentColumn+1);String style=atts.getValue("s");currentStyle=style==null?-1:nonnegativeInt(style);
                if(currentStyle>=0&&(styles==null||currentStyle>=styles.getNumCellStyles()))throw fail(ParseFailure.Code.INVALID_XLSX);
                type=atts.getValue("t");if(type!=null&&!Set.of("n","s","b","e","str","inlineStr","d").contains(type))throw fail(ParseFailure.Code.INVALID_XLSX);value.setLength(0);valueSeen=false;inlineSeen=false;
            }else if(name.equals("is")){
                if(!inCell||!"inlineStr".equals(type)||inlineSeen)throw fail(ParseFailure.Code.INVALID_XLSX);inInline=true;inlineSeen=true;
            }else if(name.equals("v")){
                if(!inCell||"inlineStr".equals(type)||valueSeen||inValue)throw fail(ParseFailure.Code.INVALID_XLSX);valueSeen=true;inValue=true;
            }else if(name.equals("t")){
                if(inCell){if(!inInline||inValue)throw fail(ParseFailure.Code.INVALID_XLSX);inValue=true;}
            }else if(name.equals("f"))throw fail(ParseFailure.Code.FORMULA_NOT_ALLOWED);
        }
        @Override public void characters(char[] chars,int start,int count){if(inValue){if(value.length()+count>ImportLimits.MAX_CELL_CHARS)throw fail(ParseFailure.Code.RESOURCE_LIMIT);value.append(chars,start,count);}}
        @Override public void endElement(String uri,String name,String qualified){
            if(!MAIN.equals(uri))return;
            if(name.equals("v")||name.equals("t"))inValue=false;
            else if(name.equals("is"))inInline=false;
            else if(name.equals("c")){cells.add(cell());inCell=false;currentColumn=-1;currentStyle=-1;type=null;}
            else if(name.equals("row")){finishRow();rowNumber=0;}
        }
        private SourceCell cell(){
            String lexical=value.toString(),display=lexical;cellText(lexical);CellKind kind;
            String format=currentStyle<0?null:styles.getStyleAt(currentStyle).getDataFormatString();
            if("s".equals(type)){int index=nonnegativeInt(lexical);if(index>=shared.getUniqueCount())throw fail(ParseFailure.Code.INVALID_XLSX);display=shared.getItemAt(index).getString();kind=CellKind.TEXT;}
            else if("b".equals(type)){if(!Set.of("0","1").contains(lexical))throw fail(ParseFailure.Code.INVALID_XLSX);kind=CellKind.BOOLEAN;display="1".equals(lexical)?"true":"false";}
            else if("e".equals(type))kind=CellKind.ERROR;
            else if("d".equals(type)){kind=CellKind.ISO_DATE;}
            else if("inlineStr".equals(type)||"str".equals(type))kind=CellKind.TEXT;
            else if(lexical.isEmpty())kind=CellKind.BLANK;
            else{
                kind=CellKind.NUMERIC;
                try{double number=Double.parseDouble(lexical);if(!Double.isFinite(number))throw new NumberFormatException();
                    if(currentStyle>=0){var style=styles.getStyleAt(currentStyle);display=formatter.formatRawCellContents(number,style.getDataFormat(),format,epoch==DateEpoch.MAC_1904);}
                }catch(NumberFormatException invalid){throw fail(ParseFailure.Code.INVALID_XLSX);}
            }
            cellText(display);return new SourceCell(currentColumn,kind,lexical,display,currentStyle<0?null:currentStyle,format);
        }
        private void finishRow(){
            budget.check();if(rowNumber<headerRow)return;
            if(rowNumber==headerRow){headerSeen=true;while(headers.size()<width)headers.add(null);for(SourceCell cell:cells)headers.set(cell.columnIndex(),cell.display());return;}
            if(cells.isEmpty()||cells.stream().allMatch(cell->cell.display()==null||cell.display().isEmpty()))return;
            if(rows.size()>=ImportLimits.MAX_DATA_ROWS)throw fail(ParseFailure.Code.TOO_MANY_ROWS);
            rows.add(new SourceRow(rows.size()+1L,rowNumber,null,null,List.copyOf(cells)));
        }
        ParsedArtifact result(List<SheetInfo> sheets,int selected){
            if(!rootSeen)throw fail(ParseFailure.Code.INVALID_XLSX);
            if(!headerSeen||headers.stream().allMatch(header->header==null||header.isEmpty()))throw fail(ParseFailure.Code.HEADER_MISSING);
            if(rows.isEmpty())throw fail(ParseFailure.Code.EMPTY_DATA);
            while(headers.size()<width)headers.add(null);
            return new ParsedArtifact(State.PARSED,ImportFormat.XLSX,sheets,selected,headerRow,epoch,ParsedArtifact.columns(headers),rows,ImportFileParser.VERSION,ImportLimits.RESOURCE_POLICY_VERSION);
        }
        private static int column(String reference,int row,int implicit){
            if(reference==null)return implicit;
            if(!reference.matches("[A-Z]{1,3}[1-9][0-9]{0,6}"))throw fail(ParseFailure.Code.INVALID_XLSX);
            int index=0,position=0;while(position<reference.length()&&reference.charAt(position)>='A'&&reference.charAt(position)<='Z')index=index*26+reference.charAt(position++)-'A'+1;
            if(positiveInt(reference.substring(position))!=row)throw fail(ParseFailure.Code.INVALID_XLSX);return index-1;
        }
    }
}
