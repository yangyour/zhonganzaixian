package cn.mop.adapters.monitoring;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeviceConnectionCheckTest {
    static Map<String,Object> report(String state,boolean read){return Map.of("selected_mode",Map.of("state",state,"protected_read_verified",read));}
    @Test void versionSuccessCannotHideLaterMetricDenial(){assertEquals(2,DeviceConnectionCheck.exitCode(report("ACCESS_DENIED",true)));assertEquals(2,DeviceConnectionCheck.exitCode(report("METRIC_NOT_PROVIDED",true)));}
    @Test void authenticationAloneIsNotSuccessfulRead(){assertEquals(2,DeviceConnectionCheck.exitCode(report("READ_OK",false)));assertEquals(2,DeviceConnectionCheck.exitCode(report("PASSWORD_EXPIRED",false)));}
    @Test void successRequiresExplicitReadOutcome(){assertEquals(0,DeviceConnectionCheck.exitCode(report("READ_OK",true)));assertEquals(0,DeviceConnectionCheck.exitCode(report("VERSION_READ_OK_RESOURCE_SCOPE_PENDING",true)));}
    @Test void invalidInputAndUnknownOutcomeCannotBeSuccess(){assertEquals(1,DeviceConnectionCheck.exitCode(Map.of("state","INVALID_PRIVATE_CONFIGURATION")));assertEquals(1,DeviceConnectionCheck.exitCode(report("INVALID_PRIVATE_CONFIGURATION",false)));assertEquals(2,DeviceConnectionCheck.exitCode(report("UNKNOWN",true)));}
}
