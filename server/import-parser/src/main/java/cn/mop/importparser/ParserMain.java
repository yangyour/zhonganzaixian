package cn.mop.importparser;

import cn.mop.domain.assetimport.source.*;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Credential-free parser protocol: one bounded request and one response, never raw data logs. */
public final class ParserMain {
    private static final int MAX_REQUEST_BYTES=16_384;
    private static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private ParserMain(){}
    public static void main(String[] args){run(System.in,System.out,args);}
    static void run(InputStream input,OutputStream output,String[] args){
        Object response;
        try{
            if(args.length!=0)throw new ParseFailure(ParseFailure.Code.INVALID_REQUEST);
            byte[] bytes=input.readNBytes(MAX_REQUEST_BYTES+1);
            if(bytes.length==0||bytes.length>MAX_REQUEST_BYTES)throw new ParseFailure(ParseFailure.Code.INVALID_REQUEST);
            Request request;
            try{request=JSON.readValue(bytes,Request.class);}catch(IOException|RuntimeException invalid){throw new ParseFailure(ParseFailure.Code.INVALID_REQUEST);}
            if(request.path()==null||request.selection()==null)throw new ParseFailure(ParseFailure.Code.INVALID_REQUEST);
            Path path;
            try{
                path=Paths.get(request.path()).toAbsolutePath().normalize();
                Path workspace=Paths.get(System.getProperty("user.dir")).toRealPath();
                if(!path.startsWith(workspace)||Files.isSymbolicLink(path)||!path.toRealPath().startsWith(workspace))throw new IOException();
            }catch(IOException|RuntimeException invalid){throw new ParseFailure(ParseFailure.Code.INVALID_REQUEST);}
            ParsedArtifact result=new ImportFileParser().parse(path,request.selection());response=Map.of("status","SUCCEEDED","artifact",result);
        }catch(ParseFailure failure){response=failure(failure.code());}
        catch(Exception unsafe){response=failure(ParseFailure.Code.INTERNAL_PARSER_FAILURE);}
        try{JSON.writeValue(output,response);}catch(IOException ignored){/* Broken pipe: no diagnostic contains source values. */}
    }
    private static Map<String,String> failure(ParseFailure.Code code){return Map.of("status","FAILED","code",code.name(),"message","Asset import parser rejected the source safely");}
    public record Request(String path,ParseSelection selection){}
}
