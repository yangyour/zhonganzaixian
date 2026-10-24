package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.notification.DeliveryJournalReader;
import cn.mop.domain.notification.DeliveryJournalReader.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DeliveryJournalControllerTest {
 final String tenant="79000000-0000-4000-8000-000000000001",actor="79000000-0000-4000-8000-000000000002",id="79000000-0000-4000-8000-000000000003",command="79000000-0000-4000-8000-000000000004";final Instant at=Instant.parse("2026-10-24T00:00:00Z");
 final PlatformRepository platform=mock(PlatformRepository.class);final DeliveryJournalReader reader=mock(DeliveryJournalReader.class);
 DeliveryJournalController controller(boolean enabled){return new DeliveryJournalController(platform,reader,new ObjectMapper().findAndRegisterModules(),new MockEnvironment().withProperty("MOP_DELIVERY_JOURNAL_READ_ENABLED",String.valueOf(enabled)));}
 MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.setUserPrincipal(()->actor);r.getSession().setAttribute("mop.tenant_id",tenant);when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",7,true)));return r;}
 Access scope(){return new Access(UUID.fromString(tenant),UUID.fromString(actor),7);}
 Entry entry(String status){return new Entry(UUID.fromString(tenant),UUID.fromString(id),UUID.randomUUID(),UUID.randomUUID(),"EMAIL",UUID.randomUUID(),"INITIAL",2,status,at,at.plusSeconds(2),at,1,1,UUID.randomUUID(),UUID.fromString(command),"a".repeat(64));}
 @Test void defaultDisabledDoesNotReadLedger(){assertEquals(503,assertThrows(DomainException.class,()->controller(false).list(request())).status);verifyNoInteractions(reader);}
 @Test void recordedDeliveredNeverBecomesVerifiedDeliveryCount(){var r=request();when(reader.list(any(),isNull(),isNull(),eq(10))).thenReturn(new Page(scope(),at,10,1,List.of(entry("DELIVERED")),null));var result=(Map<?,?>)controller(true).list(r);var data=(Map<?,?>)result.get("data");assertEquals(false,data.get("provider_receipt_verified"));assertEquals(false,data.get("provider_lookup_invoked"));assertNull(data.get("verified_delivered_count"));var row=(Map<?,?>)((List<?>)data.get("items")).get(0);assertEquals("DELIVERED",row.get("recorded_status"));assertEquals(false,row.get("provider_receipt_verified"));assertNull(row.get("verified_delivered_count"));}
 @Test void membershipChangedDuringReadRejectsData(){var r=request();when(reader.list(any(),any(),any(),anyInt())).thenAnswer(c->{when(platform.membership(actor,tenant)).thenReturn(Optional.empty());return new Page(scope(),at,10,0,List.of(),null);});assertEquals(401,assertThrows(DomainException.class,()->controller(true).list(r)).status);}
 @Test void callerCannotOverrideTenantOrFilterOnlyCurrentPageStatus(){for(String key:List.of("tenant_id","status","provider","before")){var r=request();r.addParameter(key,"bad");assertEquals(400,assertThrows(DomainException.class,()->controller(true).list(r)).status);}verifyNoInteractions(reader);}
 @Test void mspCannotInheritPrivilegedAuditRead(){var r=request();when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"msp",7,true)));assertEquals(403,assertThrows(DomainException.class,()->controller(true).list(r)).status);verifyNoInteractions(reader);}
 @Test void differentReturnedScopeIsRejected(){var r=request();when(reader.list(any(),any(),any(),anyInt())).thenReturn(new Page(new Access(UUID.randomUUID(),UUID.fromString(actor),7),at,10,0,List.of(),null));assertEquals(503,assertThrows(DomainException.class,()->controller(true).list(r)).status);}
 @Test void originalCommandRecoveryNeverAuthorizesResend(){var r=request();var original=new Step(1,UUID.fromString(command),"START",UUID.randomUUID(),null,null,at,at,at);when(reader.recover(any(),eq(UUID.fromString(id)),eq(UUID.fromString(command)))).thenReturn(new Recovery(scope(),at,entry("UNKNOWN"),original));var result=(Map<?,?>)controller(true).recover(id,command,r);var data=(Map<?,?>)result.get("data");assertEquals(false,data.get("fresh_send_authorized"));assertEquals("LOCAL_RECORDED_COMMAND_ONLY_NOT_PROVIDER_LOOKUP",data.get("recovery_scope"));assertEquals(false,data.get("automatic_send_enabled"));}
 @Test void wrongRecoveredCommandDoesNotBecomeRequestedAcknowledgment(){var r=request();when(reader.recover(any(),any(),any())).thenReturn(new Recovery(scope(),at,entry("UNKNOWN"),new Step(1,UUID.randomUUID(),"START",UUID.randomUUID(),null,null,at,at,at)));assertEquals(503,assertThrows(DomainException.class,()->controller(true).recover(id,command,r)).status);}
}
