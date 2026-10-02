package cn.mop.importparser;

import cn.mop.domain.assetimport.source.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CsvBoundaryTest {
    @TempDir Path temporary;
    private final ImportFileParser parser=new ImportFileParser();
    private Path source(byte[] bytes)throws Exception{Path file=temporary.resolve("MOP-FAKE.csv");Files.write(file,bytes);return file;}
    private ParsedArtifact parse(byte[] bytes,String encoding,String delimiter,int header)throws Exception{return parser.parse(source(bytes),new ParseSelection(ImportFormat.CSV,encoding,delimiter,null,header));}
    private void rejects(byte[] bytes,String encoding,ParseFailure.Code code)throws Exception{Path file=source(bytes);assertEquals(code,assertThrows(ParseFailure.class,()->parser.parse(file,new ParseSelection(ImportFormat.CSV,encoding,",",null,1))).code());}
    @Test void multilineRecordsKeepPhysicalLinesLogicalRecordsAndDuplicateHeaders()throws Exception{
        var result=parse("ignored\r\n名称,名称,备注\r\n\"设备\r\n甲\",r,MOP-FAKE\r\n\r\nnext,r,text\r\n".getBytes(StandardCharsets.UTF_8),"UTF-8",",",2);
        assertEquals(List.of("名称","名称","备注"),result.columns().stream().map(ParsedArtifact.SourceColumn::header).toList());
        var first=result.rows().get(0);assertEquals(3,first.logicalRecordNumber());assertEquals(3,first.sourceRowNumber());assertEquals(3L,first.physicalStartLine());assertEquals(4L,first.physicalEndLine());assertEquals("设备\r\n甲",first.cells().get(0).lexical());
        var second=result.rows().get(1);assertEquals(5,second.logicalRecordNumber());assertEquals(6L,second.physicalStartLine());assertEquals(6L,second.physicalEndLine());
    }
    @Test void declaredLegacyEncodingAndDelimiterAreHonoredWithoutGuessing()throws Exception{
        var result=parse("名称;备注\n设备;MOP-FAKE\n".getBytes(Charset.forName("GB18030")),"GB18030",";",1);assertEquals("设备",result.rows().get(0).cells().get(0).lexical());
    }
    @Test void strictDecoderRejectsMalformedUtf8AndUtf32Bom()throws Exception{
        rejects(new byte[]{'a',',','b','\n',(byte)0xc3,0x28,',','x'},"UTF-8",ParseFailure.Code.INVALID_ENCODING);
        rejects(new byte[]{(byte)0xff,(byte)0xfe,0,0,'a',0,0,0},"UTF-16LE",ParseFailure.Code.INVALID_ENCODING);
    }
    @Test void utf16RequiresMatchingBomAndRetainsText()throws Exception{
        String text="名称,备注\n设备,MOP-FAKE\n";
        for(String encoding:List.of("UTF-16LE","UTF-16BE")){
            byte[] plain=text.getBytes(Charset.forName(encoding));byte[] bytes=new byte[plain.length+2];bytes[0]=(byte)(encoding.endsWith("LE")?0xff:0xfe);bytes[1]=(byte)(encoding.endsWith("LE")?0xfe:0xff);System.arraycopy(plain,0,bytes,2,plain.length);
            assertEquals("设备",parse(bytes,encoding,",",1).rows().get(0).cells().get(0).display());
            rejects(plain,encoding,ParseFailure.Code.INVALID_ENCODING);
            rejects(bytes,encoding.endsWith("LE")?"UTF-16BE":"UTF-16LE",ParseFailure.Code.INVALID_ENCODING);
        }
    }
    @Test void incompleteQuotesAndTrailingQuoteDataFailClosed()throws Exception{
        for(String input:List.of("a,b\n\"unterminated,x","a,b\n\"x\"garbage,y"))rejects(input.getBytes(StandardCharsets.UTF_8),"UTF-8",ParseFailure.Code.INVALID_CSV);
    }
    @Test void rowColumnCellAndSourceByteLimitsAreEnforced()throws Exception{
        StringBuilder text=new StringBuilder("a,b\n");for(int index=0;index<500;index++)text.append(index).append(",MOP-FAKE\n");assertEquals(500,parse(text.toString().getBytes(StandardCharsets.UTF_8),"UTF-8",",",1).rows().size());
        text.append("501,MOP-FAKE\n");rejects(text.toString().getBytes(StandardCharsets.UTF_8),"UTF-8",ParseFailure.Code.TOO_MANY_ROWS);
        rejects((String.join(",",Collections.nCopies(65,"header"))+"\nx\n").getBytes(StandardCharsets.UTF_8),"UTF-8",ParseFailure.Code.RESOURCE_LIMIT);
        rejects(("header\n"+"x".repeat(4097)+"\n").getBytes(StandardCharsets.UTF_8),"UTF-8",ParseFailure.Code.RESOURCE_LIMIT);
        Path large=source(new byte[(int)ImportLimits.MAX_SOURCE_BYTES+1]);assertEquals(ParseFailure.Code.RESOURCE_LIMIT,assertThrows(ParseFailure.class,()->parser.parse(large,new ParseSelection(ImportFormat.CSV,"UTF-8",",",null,1))).code());
    }
}
