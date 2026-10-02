package cn.mop.importparser;

import cn.mop.domain.assetimport.source.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ParserProtocolTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static String response(String request)throws Exception{ByteArrayOutputStream output=new ByteArrayOutputStream();ParserMain.run(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)),output,new String[0]);return output.toString(StandardCharsets.UTF_8);}
    @Test void successIsExactlyOneJsonResponseAndRawArtifactIsOnlyItsPayload()throws Exception{
        Path source=Files.createTempFile(Path.of(System.getProperty("user.dir")),"MOP-FAKE-protocol-",".csv");
        try{
            Files.writeString(source,"名称,备注\nMOP-FAKE,PRIVATE-RAW-CELL\n",StandardCharsets.UTF_8);
            String request=JSON.writeValueAsString(Map.of("path",source.toString(),"selection",Map.of("declared_format","CSV","encoding","UTF-8","delimiter",",","header_row",1)));
            String response=response(request);var node=JSON.readTree(response);assertEquals("SUCCEEDED",node.get("status").asText());assertEquals("PRIVATE-RAW-CELL",node.at("/artifact/rows/0/cells/1/lexical").asText());assertEquals(2,node.size());assertFalse(response.contains(source.toString()));
        }finally{Files.deleteIfExists(source);}
    }
    @Test void malformedEnumsPathsDuplicateKeysAndMultipleRequestsReturnOnlySafeFailure()throws Exception{
        for(String request:List.of("{\"path\":\"PRIVATE-SOURCE-PATH\",\"selection\":{\"declared_format\":\"PRIVATE-ENUM-VALUE\"}}","{\"path\":\"PRIVATE-SOURCE-PATH\",\"selection\":{\"declared_format\":\"CSV\",\"encoding\":\"UTF-8\",\"delimiter\":\",\",\"header_row\":1}}","{\"path\":\"a\",\"path\":\"b\"}","{} {}"," ".repeat(16_385))){
            String response=response(request);var node=JSON.readTree(response);assertEquals("FAILED",node.get("status").asText());assertEquals("INVALID_REQUEST",node.get("code").asText());assertEquals(3,node.size());assertFalse(response.contains("PRIVATE"));assertFalse(response.contains("Exception"));
        }
    }
    @Test void cooperativeDeadlineUsesMonotonicClockAndIncludesInterruptedThread(){
        AtomicLong clock=new AtomicLong(100);ParseBudget budget=new ParseBudget(clock::get,20);budget.check();clock.addAndGet(19_999_999);budget.check();clock.incrementAndGet();assertEquals(ParseFailure.Code.RESOURCE_LIMIT,assertThrows(ParseFailure.class,budget::check).code());
        Thread.currentThread().interrupt();try{assertEquals(ParseFailure.Code.RESOURCE_LIMIT,assertThrows(ParseFailure.class,()->new ParseBudget().check()).code());}finally{Thread.interrupted();}
    }
    @Test void xmlElementBudgetBoundsEmptyNodesAndIsSharedAcrossParts()throws Exception{
        SafeXml.Context context=new SafeXml.Context(new ParseBudget());
        byte[] part=("<root>"+"<x/>".repeat(ImportLimits.MAX_XML_ELEMENTS/2-1)+"</root>").getBytes(StandardCharsets.UTF_8);
        SafeXml.parse(new ByteArrayInputStream(part),new org.xml.sax.helpers.DefaultHandler(),context);
        SafeXml.parse(new ByteArrayInputStream(part),new org.xml.sax.helpers.DefaultHandler(),context);
        assertEquals(ParseFailure.Code.RESOURCE_LIMIT,assertThrows(ParseFailure.class,()->SafeXml.parse(new ByteArrayInputStream("<root/>".getBytes(StandardCharsets.UTF_8)),new org.xml.sax.helpers.DefaultHandler(),context)).code());
    }
}
