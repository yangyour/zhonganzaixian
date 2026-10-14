package cn.mop.adapters.scp;

import cn.mop.adapters.http.DeviceHttpsTransport;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

/** Fixed-origin SCP transport; test TLS is explicitly selected and isolated per client. */
public final class ScpHttpsTransport implements ScpClient.Exchange,AutoCloseable {
    private final DeviceHttpsTransport transport;
    public ScpHttpsTransport(URI origin,Path caFile){this(origin,caFile==null?ScpSettings.Tls.SYSTEM:ScpSettings.Tls.CA_FILE,caFile);}
    public ScpHttpsTransport(URI origin,ScpSettings.Tls mode,Path caFile){
        try{transport=new DeviceHttpsTransport(ScpProtocol.origin(origin.toString()),DeviceHttpsTransport.Mode.valueOf(mode.name()),caFile);}
        catch(DeviceHttpsTransport.Failure failure){throw ScpProtocol.fail(failure.reason);}
    }
    public ScpClient.Response execute(ScpProtocol.Request request,Map<String,String> headers){
        if(!request.method().equals("GET")&&!(request.method().equals("POST")&&request.path().equals("/janus/authenticate")))throw ScpProtocol.fail("WRITE_METHOD_NOT_ALLOWED");
        try{var response=transport.execute(request.method(),request.target(),headers,request.body());return new ScpClient.Response(response.status(),response.contentType(),response.bytes());}
        catch(DeviceHttpsTransport.Failure failure){throw ScpProtocol.fail(failure.reason);}
    }
    public void close(){transport.close();}
}
