package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.persistence.DeliveryDeadLetterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class DeliveryDeadLetterControllerTest {
 static final String TENANT="79000000-0000-4000-8000-000000000001",ACTOR="79000000-0000-4000-8000-000000000002",ID="79000000-0000-4000-8000-000000000003",COMMAND="79000000-0000-4000-8000-000000000004",POOL="79000000-0000-4000-8000-000000000005";
 final PlatformRepository platform=mock(PlatformRepository.class);final DeliveryDeadLetterService service=mock(DeliveryDeadLetterService.class);final DeliveryDeadLetterController controller=new DeliveryDeadLetterController(platform,service,new ObjectMapper());
 static Map<String,Object> receipt(){var m=new LinkedHashMap<String,Object>();m.put("tenant_id",TENANT);m.put("actor_id",ACTOR);m.put("delivery_id",ID);m.put("resource_pool_id",POOL);m.put("command_id",COMMAND);m.put("applied_version",3);m.put("recorded_status_at_application","DEAD_LETTER");m.put("reason","Synthetic reason");m.put("applied_at","2026-10-24T12:00:00Z");m.put("source","LOCAL_FAILED_DECLARATION");m.put("provider_receipt_verified",false);m.put("automatic_send_enabled",false);m.put("fresh_send_authorized",false);m.put("receipt_scope","ORIGINAL_COMMAND_NOT_CURRENT_HEAD");return m;}
 MockHttpServletRequest request(String role,String body){var r=new MockHttpServletRequest("POST","/api/v1/notification-deliveries/"+ID+"/dead-letter");r.setContentType("application/json");r.setContent(body.getBytes(StandardCharsets.UTF_8));r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);r.addHeader("Idempotency-Key",COMMAND);when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,role,7,true)));return r;}
 MockHttpServletRequest request(){return request("padmin","{\"expected_version\":2,\"reason\":\"Synthetic reason\"}");}
 @Test void exactCommandReplyKeepsUnverifiedFlagsAndChecksIdentityAgain(){var r=request();when(service.dispose(any(),any(),anyLong(),any(),anyString(),anyString())).thenReturn(receipt());assertNotNull(controller.dispose(ID,r));verify(platform,times(2)).membership(ACTOR,TENANT);}
 @Test void roleAndMissingCapabilityAreRejectedBeforeAnyMutation(){var r=request("msp","{}");assertEquals(403,assertThrows(DomainException.class,()->controller.dispose(ID,r)).status);verifyNoInteractions(service);}
 @Test void strictBodyAndOriginalCommandAreRequired(){for(String body:List.of("{}","{\"expected_version\":-1,\"reason\":\"x\"}","{\"expected_version\":12000,\"reason\":\"x\"}","{\"expected_version\":2,\"reason\":\" \"}","{\"expected_version\":2,\"reason\":\"x\",\"send\":true}","{\"expected_version\":2,\"reason\":\"x\",\"reason\":\"y\"}")){var r=request("padmin",body);assertEquals(400,assertThrows(DomainException.class,()->controller.dispose(ID,r)).status);}var r=request();r.addHeader("Idempotency-Key",COMMAND);assertEquals(400,assertThrows(DomainException.class,()->controller.dispose(ID,r)).status);verifyNoInteractions(service);}
 @ParameterizedTest @ValueSource(strings={"inactive","removed","version","role","capabilities","tenant","session","actor"})
 void afterCommitRevocationReturnsUnconfirmedWithoutPrivateReceipt(String change){var r=request();when(service.dispose(any(),any(),anyLong(),any(),anyString(),anyString())).thenAnswer(c->{switch(change){
 case "inactive"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,false)));case "removed"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.empty());case "version"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",8,true)));case "role"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,true)));case "capabilities"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,true,List.of("audit:read"))));case "tenant"->r.getSession().setAttribute("mop.tenant_id",ACTOR);case "session"->r.getSession().invalidate();case "actor"->r.setUserPrincipal(()->TENANT);}
 return receipt();});var e=assertThrows(DomainException.class,()->controller.dispose(ID,r));assertEquals(503,e.status);assertEquals("DELIVERY_DISPOSITION_RESULT_UNCONFIRMED",e.code);}
 @Test void mismatchedReturnedReceiptIsNotAcknowledged(){var r=request();var out=receipt();out.put("fresh_send_authorized",true);when(service.dispose(any(),any(),anyLong(),any(),anyString(),anyString())).thenReturn(out);assertEquals(503,assertThrows(DomainException.class,()->controller.dispose(ID,r)).status);}
 @ParameterizedTest @ValueSource(strings={"extra","fractional","time","pool"})
 void corruptedStoredRepliesNeverLeakOrAcknowledge(String kind){var r=request();var out=receipt();switch(kind){case "extra"->out.put("unexpected_private_field","SYNTHETIC_PRIVATE");case "fractional"->out.put("applied_version",3.5);case "time"->out.put("applied_at","invalid");case "pool"->out.put("resource_pool_id","invalid");}when(service.dispose(any(),any(),anyLong(),any(),anyString(),anyString())).thenReturn(out);var e=assertThrows(DomainException.class,()->controller.dispose(ID,r));assertEquals(503,e.status);assertFalse(e.getMessage().contains("PRIVATE"));}
}
