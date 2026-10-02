package cn.mop.importparser;

import cn.mop.domain.assetimport.source.*;
import cn.mop.domain.assetimport.source.ParsedArtifact.*;
import org.apache.commons.csv.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

final class CsvSourceParser {
    private CsvSourceParser(){}
    static ParsedArtifact parse(Path input,ParseSelection selection,ParseBudget budget)throws IOException{
        CSVFormat format=CSVFormat.RFC4180.builder().setDelimiter(selection.delimiter()).setIgnoreEmptyLines(false).setTrim(false).setIgnoreSurroundingSpaces(false).setLenientEof(false).setTrailingData(false).get();
        List<String> headers=null;List<SourceRow> rows=new ArrayList<>();int width=0;long previousLine=0,text=0,dataRows=0;
        try(Reader reader=reader(input,selection.encoding(),budget);CSVParser parser=CSVParser.parse(reader,format)){
            for(CSVRecord record:parser){
                budget.check();
                long number=record.getRecordNumber(),end=Math.max(1,parser.getCurrentLineNumber()),start=previousLine+1;previousLine=end;
                if(number>ImportLimits.MAX_SOURCE_ROW_INDEX||record.size()>ImportLimits.MAX_COLUMNS)throw fail(ParseFailure.Code.RESOURCE_LIMIT);
                List<String> values=new ArrayList<>();for(String value:record){
                    if(value.length()>ImportLimits.MAX_CELL_CHARS||value.codePoints().anyMatch(code->Character.isISOControl(code)&&code!='\t'&&code!='\r'&&code!='\n'))throw fail(ParseFailure.Code.RESOURCE_LIMIT);
                    text+=value.length();if(text>ImportLimits.MAX_TOTAL_TEXT_CHARS)throw fail(ParseFailure.Code.RESOURCE_LIMIT);values.add(value);
                }
                if(number==selection.headerRow()){
                    if(values.stream().allMatch(String::isEmpty))throw fail(ParseFailure.Code.HEADER_MISSING);headers=values;width=values.size();continue;
                }
                if(number<selection.headerRow())continue;
                if(values.stream().allMatch(String::isEmpty))continue;
                if(dataRows++>=ImportLimits.MAX_DATA_ROWS)throw fail(ParseFailure.Code.TOO_MANY_ROWS); // Consume and reject record 501; never silently truncate.
                width=Math.max(width,values.size());List<SourceCell> cells=new ArrayList<>();for(int index=0;index<values.size();index++)cells.add(new SourceCell(index,CellKind.TEXT,values.get(index),values.get(index),null,null));
                rows.add(new SourceRow(number,(int)number,start,end,cells));
            }
        }catch(UncheckedIOException invalid){throw fail(isEncodingFailure(invalid)?ParseFailure.Code.INVALID_ENCODING:ParseFailure.Code.INVALID_CSV);}
        catch(CharacterCodingException invalid){throw fail(ParseFailure.Code.INVALID_ENCODING);}
        if(headers==null)throw fail(ParseFailure.Code.HEADER_MISSING);if(rows.isEmpty())throw fail(ParseFailure.Code.EMPTY_DATA);
        while(headers.size()<width)headers.add(null);
        return new ParsedArtifact(State.PARSED,ImportFormat.CSV,List.of(new SheetInfo(0,"CSV",false)),0,selection.headerRow(),DateEpoch.NOT_APPLICABLE,ParsedArtifact.columns(headers),rows,ImportFileParser.VERSION,ImportLimits.RESOURCE_POLICY_VERSION);
    }
    private static Reader reader(Path input,String encoding,ParseBudget budget)throws IOException{
        PushbackInputStream stream=new PushbackInputStream(Files.newInputStream(input),4);
        try{
            byte[] prefix=stream.readNBytes(4);int bom=0;String detected=null;
            if(prefix.length==4&&((prefix[0]==0&&prefix[1]==0&&(prefix[2]&255)==254&&(prefix[3]&255)==255)||((prefix[0]&255)==255&&(prefix[1]&255)==254&&prefix[2]==0&&prefix[3]==0)))throw fail(ParseFailure.Code.INVALID_ENCODING);
            if(prefix.length>=3&&(prefix[0]&255)==239&&(prefix[1]&255)==187&&(prefix[2]&255)==191){bom=3;detected="UTF-8";}
            else if(prefix.length>=2&&(prefix[0]&255)==255&&(prefix[1]&255)==254){bom=2;detected="UTF-16LE";}
            else if(prefix.length>=2&&(prefix[0]&255)==254&&(prefix[1]&255)==255){bom=2;detected="UTF-16BE";}
            if(detected!=null&&!detected.equals(encoding)||encoding.startsWith("UTF-16")&&detected==null)throw fail(ParseFailure.Code.INVALID_ENCODING);
            if(prefix.length>bom)stream.unread(prefix,bom,prefix.length-bom);
            CharsetDecoder decoder=Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            return new FilterReader(new InputStreamReader(stream,decoder)){
                private long chars;
                private void consumed(int count){budget.check();if(count>0&&(chars+=count)>ImportLimits.MAX_TOTAL_TEXT_CHARS)throw fail(ParseFailure.Code.RESOURCE_LIMIT);}
                @Override public int read()throws IOException{int result=super.read();consumed(result<0?0:1);return result;}
                @Override public int read(char[] target,int start,int length)throws IOException{int result=super.read(target,start,length);consumed(result);return result;}
            };
        }catch(IOException|RuntimeException invalid){stream.close();throw invalid;}
    }
    private static boolean isEncodingFailure(Throwable failure){for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof CharacterCodingException)return true;return false;}
    private static ParseFailure fail(ParseFailure.Code code){return new ParseFailure(code);}
}
