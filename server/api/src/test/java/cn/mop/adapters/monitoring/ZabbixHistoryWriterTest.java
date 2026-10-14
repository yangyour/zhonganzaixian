package cn.mop.adapters.monitoring;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZabbixHistoryWriterTest {
    @Test void acceptsRealSevenZeroNumericItemIdsButRejectsWrongOrFloatingIds(){var actual=ZabbixReadClient.JSON.valueToTree(Map.of("response","success","data",List.of(Map.of("itemid",50765))));assertDoesNotThrow(()->ZabbixHistoryWriter.validate(actual,Set.of("50765")));assertThrows(IllegalStateException.class,()->ZabbixHistoryWriter.validate(actual,Set.of("50766")));var invalid=ZabbixReadClient.JSON.valueToTree(Map.of("response","success","data",List.of(Map.of("itemid",50765.0))));assertThrows(IllegalStateException.class,()->ZabbixHistoryWriter.validate(invalid,Set.of("50765")));}
    @Test void writeAcknowledgementUsesObjectEnvelopeNotReadArray()throws Exception{byte[] bytes=ZabbixReadClient.JSON.writeValueAsBytes(Map.of("jsonrpc","2.0","id",1,"result",Map.of("response","success","data",List.of(Map.of("itemid","1")))));assertDoesNotThrow(()->ZabbixHistoryWriter.decode(200,"application/json",bytes,1,Set.of("1")));assertThrows(IllegalStateException.class,()->ZabbixHistoryWriter.decode(200,"application/json",bytes,2,Set.of("1")));assertThrows(IllegalStateException.class,()->ZabbixHistoryWriter.decode(403,"application/json",bytes,1,Set.of("1")));}
    @Test void partialErrorsAreNotSuccess(){var json=ZabbixReadClient.JSON.valueToTree(Map.of("response","success","data",List.of(Map.of("itemid","1"),Map.of("itemid","2","error","PRIVATE_SOURCE"))));var failure=assertThrows(IllegalStateException.class,()->ZabbixHistoryWriter.validate(json,Set.of("1","2")));assertFalse(failure.toString().contains("PRIVATE"));}
    @Test void acceptsEveryExplicitItemOnly(){var json=ZabbixReadClient.JSON.valueToTree(Map.of("response","success","data",List.of(Map.of("itemid","1"),Map.of("itemid","2"))));assertDoesNotThrow(()->ZabbixHistoryWriter.validate(json,Set.of("1","2")));assertThrows(IllegalStateException.class,()->ZabbixHistoryWriter.validate(json,Set.of("1","3")));}
    @Test void duplicateOrMissingAcknowledgementsFail(){for(var rows:List.of(List.of(Map.of("itemid","1")),List.of(Map.of("itemid","1"),Map.of("itemid","1"))))assertThrows(IllegalStateException.class,()->ZabbixHistoryWriter.validate(ZabbixReadClient.JSON.valueToTree(Map.of("response","success","data",rows)),Set.of("1","2")));}
    @Test void endpointAndItemScopeCannotExpand(){assertThrows(IllegalArgumentException.class,()->new ZabbixHistoryWriter("http://example.com/api_jsonrpc.php","a".repeat(64),Set.of("1","2","3")));try(var w=new ZabbixHistoryWriter("http://127.0.0.1:10020/api_jsonrpc.php","a".repeat(64),Set.of("1","2","3"))){assertThrows(IllegalArgumentException.class,()->w.push(Map.of("4","10"),java.time.Instant.now()));}}
}
