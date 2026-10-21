package cn.mop.api;
import cn.mop.adapters.monitoring.ZabbixReadClient;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class ZabbixReadEnvelopeTest {
    byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    @Test void oversizedIntegerCannotWrapIntoMatchingRequestId(){assertThrows(ZabbixReadClient.ReadFailure.class,()->ZabbixReadClient.decode(200,"application/json",bytes("{\"jsonrpc\":\"2.0\",\"id\":18446744073709551617,\"result\":[]}"),1));}
    @Test void protocolVersionMustBeStringNotCoercedNumber(){assertThrows(ZabbixReadClient.ReadFailure.class,()->ZabbixReadClient.decode(200,"application/json",bytes("{\"jsonrpc\":2.0,\"id\":1,\"result\":[]}"),1));}
    @Test void ordinaryExactEnvelopeStillWorks(){assertTrue(ZabbixReadClient.decode(200,"application/json",bytes("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[]}"),1).isArray());}
}
