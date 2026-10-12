package cn.mop.adapters.scp;

import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Offline private-reference check only. Never starts Spring, authenticates, or opens a socket. */
public final class ScpPreparationCheck {
    private ScpPreparationCheck(){}
    public static Map<String,Object> check(byte[] configuration,Function<String,String> resolver){
        ScpSettings settings=ScpSettings.parse(configuration);List<String> problems=settings.problems(resolver);
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("configuration_status",problems.isEmpty()?"CONFIGURED_NOT_VERIFIED":"CONFIG_PENDING");
        report.put("target",settings.baseUrl.toString());report.put("api_profile","JANUS_20180725");report.put("enabled",settings.enabled);
        report.put("blockers",problems);report.put("integration_ready",false);report.put("http_requests_sent",0);
        report.put("credentials_output",false);report.put("tls_trust_verified",false);report.put("source_identity_verified",false);
        return Collections.unmodifiableMap(report);
    }
    public static void main(String[] args){
        int status=1;
        try{
            if(args.length!=2||!args[0].equals("--check"))throw ScpProtocol.fail("EXPECTED_CHECK_AND_PRIVATE_JSON_PATH");
            byte[] data;try(var input=Files.newInputStream(Path.of(args[1]))){data=input.readNBytes(16385);}
            Map<String,Object> result=check(data,System::getenv);System.out.println(ScpProtocol.JSON.writeValueAsString(result));
            status=result.get("configuration_status").equals("CONFIGURED_NOT_VERIFIED")?0:2;
        }catch(Exception failure){System.out.println("{\"configuration_status\":\"INVALID\",\"integration_ready\":false,\"http_requests_sent\":0,\"credentials_output\":false}");}
        System.exit(status);
    }
}
