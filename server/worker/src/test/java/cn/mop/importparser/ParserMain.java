package cn.mop.importparser;

import java.nio.file.*;
import java.util.*;

/** Executable protocol adversary for isolated subprocess tests; no components or real credentials. */
public final class ParserMain {
    private static final String OK="{\"status\":\"SUCCEEDED\",\"artifact\":{\"state\":\"PARSED\",\"detected_format\":\"CSV\",\"sheets\":[{\"index\":0,\"name\":\"CSV\",\"hidden\":false}],\"selected_sheet_index\":0,\"header_row\":1,\"date_epoch\":\"NOT_APPLICABLE\",\"columns\":[{\"index\":0,\"header\":\"fake\",\"target_suggestions\":[]}],\"rows\":[{\"logical_record_number\":2,\"source_row_number\":2,\"physical_start_line\":2,\"physical_end_line\":2,\"cells\":[{\"column_index\":0,\"kind\":\"TEXT\",\"lexical\":\"MOP-FAKE-isolated\",\"display\":\"MOP-FAKE-isolated\",\"style_index\":null,\"format_code\":null}]}],\"parser_version\":\"poi-5.5.1-csv-1.14.1-parser-v1\",\"resource_policy_version\":\"asset-import-technical-v1\"}}";
    public static void main(String[] args)throws Exception{
        System.in.readAllBytes();String mode=Files.readString(Path.of("source")).strip();
        if(mode.isEmpty()){System.out.print("{\"status\":\"FAILED\",\"code\":\"HEADER_MISSING\",\"message\":\"Asset import parser rejected the source safely\"}");return;}
        if(mode.equals("TIMEOUT")){Thread.sleep(60_000);return;}
        if(mode.equals("HANG_AFTER_JSON")){System.out.print(OK);System.out.flush();Thread.sleep(60_000);return;}
        if(mode.equals("STDOUT")){for(int index=0;index<17*1024;index++)System.out.print("x".repeat(1024));return;}
        if(mode.equals("STDERR")){System.err.print("PRIVATE-ISOLATED-STDERR".repeat(4000));System.err.flush();System.out.print(OK);return;}
        if(mode.equals("TWODOCS")){System.out.print(OK+"{}");return;}
        if(mode.equals("MANY_NODES")){System.out.print("{\"status\":\"SUCCEEDED\",\"artifact\":["+"null,".repeat(600_000)+"null]}");return;}
        if(mode.equals("DUPLICATE")){System.out.print(OK.replace("\"status\":\"SUCCEEDED\"","\"status\":\"SUCCEEDED\",\"status\":\"SUCCEEDED\""));return;}
        if(mode.equals("WRONG_ROW")){System.out.print(OK.replace("\"logical_record_number\":2","\"logical_record_number\":1"));return;}
        if(mode.equals("WRONG_SUGGESTION")){System.out.print(OK.replace("\"target_suggestions\":[]","\"target_suggestions\":[\"NAME\"]"));return;}
        if(mode.equals("BAD_ENUM")){System.out.print(OK.replace("\"kind\":\"TEXT\"","\"kind\":\"PRIVATE-ISOLATED-ENUM\""));return;}
        if(mode.equals("EXTRA")){System.out.print(OK.replace("\"artifact\":{","\"artifact\":{\"private_extra\":\"PRIVATE-ISOLATED-EXTRA\","));return;}
        if(mode.equals("ENV")){
            if(!Set.of("SystemRoot","SYSTEMROOT","TEMP","TMP").containsAll(System.getenv().keySet())||!Path.of(System.getenv("TEMP")).startsWith(Path.of(System.getProperty("user.dir")))||!System.getProperty("java.io.tmpdir").equals(System.getenv("TEMP"))||Runtime.getRuntime().maxMemory()>150L*1024*1024){System.out.print(OK.replace("MOP-FAKE-isolated","MOP-FAKE-UNSAFE"));return;}
        }
        System.out.print(OK);
    }
}
