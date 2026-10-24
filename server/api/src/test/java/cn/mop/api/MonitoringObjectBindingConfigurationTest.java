package cn.mop.api;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
class MonitoringObjectBindingConfigurationTest {
 final String tenant="79000000-0000-4000-8000-000000000001",pool="79000000-0000-4000-8000-000000000002",reviewer="79000000-0000-4000-8000-000000000003";
 String policy(String content){return "{\""+tenant+"\":{"+content+"}}";}
 @Test void defaultsRemainUnknown(){var p=MonitoringObjectBindingConfiguration.policies(null).apply(tenant);assertFalse(p.configured());assertNull(p.reference());assertNull(p.pools());assertNull(p.verificationTtlSeconds());}
 @Test void policyOrderAndWhitespaceDoNotChangeHash(){String a="\"reference\":\"v1\",\"pools\":[\""+pool+"\"],\"reviewers\":[\""+reviewer+"\"],\"self_approval_allowed\":false,\"verification_ttl_seconds\":60";String b="\"verification_ttl_seconds\":60, \"self_approval_allowed\":false,\"reviewers\":[\""+reviewer+"\"],\"pools\":[\""+pool+"\"],\"reference\":\"v1\"";var p=MonitoringObjectBindingConfiguration.policies(policy(a)).apply(tenant);assertTrue(p.configured());assertEquals(p,MonitoringObjectBindingConfiguration.policies(policy(b)).apply(tenant));assertNotEquals(p.reference(),MonitoringObjectBindingConfiguration.policies(policy(a.replace(":60",":61"))).apply(tenant).reference());}
 @Test void duplicateAndUnknownConfigAreRejected(){for(String value:List.of(policy("\"reference\":\"a\",\"reference\":\"b\""),policy("\"token\":\"not allowed\""),"{} {}"))assertThrows(IllegalStateException.class,()->MonitoringObjectBindingConfiguration.policies(value));}
 @Test void nullSourceDoesNotConstructClientOrInventAuthority(){var source=MonitoringObjectBindingConfiguration.sources(new MockEnvironment());assertNull(source.provider(UUID.fromString(pool)));assertNull(source.definitionSha256(UUID.fromString(pool)));}
 @Test void incompleteKnownSourceStillCannotVerify(){var env=new MockEnvironment().withProperty("MOP_MONITORING_MAPPING_SOURCES","{\""+pool+"\":{\"provider\":\"ZABBIX_7\",\"reference\":\"private-v1\",\"endpoint\":null,\"token_env\":null,\"tags\":null}}");var source=MonitoringObjectBindingConfiguration.sources(env);assertEquals("ZABBIX_7",source.provider(UUID.fromString(pool)));assertNull(source.definitionSha256(UUID.fromString(pool)));}
 @Test void externalEndpointAndInlineSecretsRejected(){for(String extra:List.of("\"endpoint\":\"https://example.com/api_jsonrpc.php\"","\"token\":\"should never be configured inline\"")){String value="{\""+pool+"\":{\"provider\":\"ZABBIX_7\",\"reference\":\"v1\","+extra+"}}";assertThrows(IllegalStateException.class,()->MonitoringObjectBindingConfiguration.sources(new MockEnvironment().withProperty("MOP_MONITORING_MAPPING_SOURCES",value)));}}
}
