package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.configuration.RuntimeConfigurationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MonitoringRefreshPolicyTest {
 final RuntimeConfigurationRepository repository=mock(RuntimeConfigurationRepository.class);
 final RuntimeConfigurationCatalog catalog=new RuntimeConfigurationCatalog(new ObjectMapper());
 final MonitoringRefreshPolicy policy=new MonitoringRefreshPolicy(repository,catalog);
 final Membership member=new Membership("55000000-0000-4000-8000-000000000003","55000000-0000-4000-8000-000000000001","viewer",2,true);
 Map<String,Object> input(String status,Object value,Map<String,Object> release){return ApiResponse.map("tenant_id",member.tenantId(),"configuration_key",MonitoringRefreshPolicy.KEY,"source","postgresql","status",status,"effective_value",value,"selected_release",release,"observed_at","2026-10-15T00:00:00Z");}
 Map<String,Object> release(String hash){return ApiResponse.map("id","55000000-0000-4000-8000-000000000004","version",4,"definition_hash",hash,"expires_at",null,"published_by","private-publisher-not-returned");}
 @Test void genuinelyUnconfiguredUsesOnlyTheDeclaredContractDefault(){when(repository.monitoringRefresh(any())).thenReturn(input("UNCONFIGURED",null,null));var r=policy.read(member,"trace");assertEquals(30,r.get("interval_seconds"));assertEquals("CONTRACT_DEFAULT",r.get("source"));assertNull(r.get("publication_id"));}
 @Test void readsActualPublishedIntervalWithoutPublisherInformation(){when(repository.monitoringRefresh(any())).thenReturn(input("ACTIVE",15,release(catalog.hash(MonitoringRefreshPolicy.KEY))));var r=policy.read(member,"trace");assertEquals(15,r.get("interval_seconds"));assertEquals("RUNTIME_PUBLICATION",r.get("source"));assertEquals(member.userId(),r.get("actor_id"));assertFalse(r.containsKey("published_by"));assertFalse(r.containsKey("value"));}
 @Test void disabledExpiredAndSchemaChangesNeverFallBackToThirty(){for(String state:List.of("DISABLED","EXPIRED")){when(repository.monitoringRefresh(any())).thenReturn(input(state,null,release(catalog.hash(MonitoringRefreshPolicy.KEY))));var r=policy.read(member,"trace");assertNull(r.get("interval_seconds"));assertEquals(false,r.get("auto_refresh_enabled"));}when(repository.monitoringRefresh(any())).thenReturn(input("ACTIVE",15,release("b".repeat(64))));assertEquals("DEFINITION_CHANGED",policy.read(member,"trace").get("reason"));}
 @Test void invalidIntervalsRemainBlocked(){for(Object value:List.of(0,-1,3601,"30",1.5,Map.of("seconds",30))){when(repository.monitoringRefresh(any())).thenReturn(input("ACTIVE",value,release(catalog.hash(MonitoringRefreshPolicy.KEY))));var r=policy.read(member,"trace");assertNull(r.get("interval_seconds"));assertEquals("INVALID_VALUE",r.get("reason"));}}
 @Test void sourceFailureAndForeignScopeAreErrorsNotDefaultValues(){when(repository.monitoringRefresh(any())).thenThrow(new DomainException(503,"UPSTREAM_UNAVAILABLE","db unavailable"));assertThrows(DomainException.class,()->policy.read(member,"trace"));var foreign=input("UNCONFIGURED",null,null);foreign.put("tenant_id","other");doReturn(foreign).when(repository).monitoringRefresh(any());assertThrows(DomainException.class,()->policy.read(member,"trace"));}
}
