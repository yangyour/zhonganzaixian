package cn.mop.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;

class ServiceObservationConfigurationTest {
    static final String TENANT="10000000-0000-4000-8000-000000000001";
    static final String SERVICE="20000000-0000-4000-8000-000000000001";
    static final String ASSET="30000000-0000-4000-8000-000000000001";
    static String valid(){return "{\""+TENANT+"\":[{\"id\":\""+SERVICE+"\",\"name\":\"业务服务\",\"asset_ids\":[\""+ASSET+"\"],\"failure_mode\":\"ANY_ASSET_UNAVAILABLE\",\"maintenance_mode\":\"ALL_ASSETS_APPROVED\",\"target_pct\":null}]}";}
    @Test void blankHasNoInventedMapping(){assertTrue(ServiceObservationConfiguration.parse("").isEmpty());}
    @Test void explicitNullTargetRemainsNull(){assertNull(ServiceObservationConfiguration.parse(valid()).get(UUID.fromString(TENANT)).get(0).targetPct());}
    @Test void policyCannotBeOmitted(){assertThrows(IllegalStateException.class,()->ServiceObservationConfiguration.parse(valid().replace("\"failure_mode\":\"ANY_ASSET_UNAVAILABLE\",","")));}
    @Test void healthyIntervalsCannotBeInjected(){assertThrows(IllegalStateException.class,()->ServiceObservationConfiguration.parse(valid().replace("\"target_pct\":null","\"target_pct\":null,\"healthy\":[]")));}
    @Test void duplicateKeysAndTrailingDocumentsFail(){assertThrows(IllegalStateException.class,()->ServiceObservationConfiguration.parse(valid().replace("\"target_pct\":null","\"target_pct\":null,\"target_pct\":100")));assertThrows(IllegalStateException.class,()->ServiceObservationConfiguration.parse(valid()+"{}"));}
    @Test void malformedAndDuplicateAssetsFail(){assertThrows(IllegalStateException.class,()->ServiceObservationConfiguration.parse(valid().replace(ASSET,"bad")));assertThrows(IllegalStateException.class,()->ServiceObservationConfiguration.parse(valid().replace("[\""+ASSET+"\"]","[\""+ASSET+"\",\""+ASSET+"\"]")));}
    @Test void targetsAreBoundedAndNotStrings(){for(String target:new String[]{"-1","100.1","\"99.9\"","99.99999"})assertThrows(IllegalStateException.class,()->ServiceObservationConfiguration.parse(valid().replace("null",target)));}
    @Test void exceptionNeverLeaksPrivateConfiguration(){String secret="do-not-copy-private-value";var e=assertThrows(IllegalStateException.class,()->ServiceObservationConfiguration.parse(valid().replace("ANY_ASSET_UNAVAILABLE",secret)));assertFalse(e.toString().contains(secret));assertNull(e.getCause());}
}
