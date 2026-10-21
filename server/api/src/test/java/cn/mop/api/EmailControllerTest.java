package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.persistence.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailControllerTest {
    final String tenant="55000000-0000-4000-8000-000000000001",actor="55000000-0000-4000-8000-000000000011";
    PlatformRepository platform;PostgresEmailRepository repository;EmailService service;EmailController controller;
    @BeforeEach void setup(){platform=mock(PlatformRepository.class);repository=mock(PostgresEmailRepository.class);service=mock(EmailService.class);controller=new EmailController(service,repository,platform);when(service.tenant()).thenReturn(tenant);when(service.sender()).thenReturn("sender@example.test");when(service.testRecipient()).thenReturn("recipient@example.test");when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",3,true)));when(platform.idempotentAuthorized(any(),any(),any(),any(),any(),anyLong(),any(),any())).thenAnswer(x->{((Runnable)x.getArgument(6)).run();return ((Supplier<?>)x.getArgument(7)).get();});}
    MockHttpServletRequest request(String path,String body){var r=new MockHttpServletRequest("POST","/api/v1"+path);r.setUserPrincipal(()->actor);r.getSession().setAttribute("mop.tenant_id",tenant);r.setAttribute("mop.trace_id","synthetic-email");r.addHeader("Idempotency-Key","synthetic-email-command");r.setContentType("application/json");r.setContent(body.getBytes(StandardCharsets.UTF_8));return r;}
    @Test void testHasFixedContentRecipientAndDurable202(){var r=request("/email/test-messages","{\"expected_version\":0}");var response=new MockHttpServletResponse();controller.test(r,response);assertEquals(202,response.getStatus());verify(repository).enqueueTest(argThat(a->a.tenantId().equals(tenant)&&a.actorId().equals(actor)),eq("sender@example.test"),eq("recipient@example.test"),eq(EmailService.TEST_SUBJECT),eq(EmailService.TEST_MESSAGE));}
    @Test void arbitraryRecipientsContentHostAndOldVersionsAreRejected(){for(String body:List.of("{\"expected_version\":0,\"recipient\":\"other@example.test\"}","{\"expected_version\":0,\"subject\":\"injected\"}","{\"expected_version\":0,\"host\":\"localhost\"}","{\"expected_version\":1}"))assertEquals(400,assertThrows(DomainException.class,()->controller.test(request("/email/test-messages",body),new MockHttpServletResponse())).status);verifyNoInteractions(repository);}
    @Test void crossTenantIs404BeforeDependencyAccess(){var r=request("/email/test-messages","{\"expected_version\":0}");r.getSession().setAttribute("mop.tenant_id","55000000-0000-4000-8000-000000000002");assertEquals(404,assertThrows(DomainException.class,()->controller.test(r,new MockHttpServletResponse())).status);verifyNoInteractions(repository);}
    @Test void viewerCannotSendAdminTest(){when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"viewer",3,true)));assertEquals(403,assertThrows(DomainException.class,()->controller.test(request("/email/test-messages","{\"expected_version\":0}"),new MockHttpServletResponse())).status);}
    @Test void missingKeyCannotEnqueue(){var r=request("/email/test-messages","{\"expected_version\":0}");r.removeHeader("Idempotency-Key");assertThrows(DomainException.class,()->controller.test(r,new MockHttpServletResponse()));verifyNoInteractions(repository);}
    @Test void disabledDispatchDoesNotEnqueue(){doThrow(new DomainException(503,"EMAIL_DISPATCH_DISABLED","disabled")).when(service).requireDispatch();assertEquals(503,assertThrows(DomainException.class,()->controller.test(request("/email/test-messages","{\"expected_version\":0}"),new MockHttpServletResponse())).status);verify(repository,never()).enqueueTest(any(),anyString(),anyString(),anyString(),anyString());}
    @Test void ownNotificationUsesCurrentActorAndOnlySourceId(){var r=request("/my/notifications/"+actor+"/email","{\"expected_version\":0}");var response=new MockHttpServletResponse();controller.notification(actor,r,response);assertEquals(202,response.getStatus());verify(repository).authorizeSend(argThat(a->a.actorId().equals(actor)),eq(actor));verify(repository).enqueueNotification(any(),eq("sender@example.test"),eq(actor));}
    @Test void queryOverridesAreRejected(){var r=request("/email/check","{}");r.setQueryString("tenant_id=other");assertEquals(400,assertThrows(DomainException.class,()->controller.check(r)).status);verify(service,never()).check();}

    @Test void exactTargetReadUsesCurrentTenantAndConfiguredChannelWithoutWrites(){
        var r=request("/email/bindings/"+actor,"");r.setMethod("GET");r.removeHeader("Idempotency-Key");controller.targetBinding(actor,r);
        verify(repository).targetBinding(argThat(a->a.tenantId().equals(tenant)&&a.actorId().equals(actor)&&a.permissionVersion()==3),eq(actor),eq("sender@example.test"));
        verify(platform,never()).idempotentAuthorized(any(),any(),any(),any(),any(),anyLong(),any(),any());
    }
    @Test void exactTargetReadRejectsQueryOverrideBeforeRepository(){var r=request("/email/bindings/"+actor,"");r.setQueryString("tenant_id=other");assertEquals(400,assertThrows(DomainException.class,()->controller.targetBinding(actor,r)).status);verifyNoInteractions(repository);}
    @Test void exactTargetReadRejectsForeignTenantBeforeRepository(){var r=request("/email/bindings/"+actor,"");r.getSession().setAttribute("mop.tenant_id","55000000-0000-4000-8000-000000000002");assertEquals(404,assertThrows(DomainException.class,()->controller.targetBinding(actor,r)).status);verifyNoInteractions(repository);}
    @Test void exactTargetReadRequiresReadCapability(){when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"viewer",3,true)));assertEquals(403,assertThrows(DomainException.class,()->controller.targetBinding(actor,request("/email/bindings/"+actor,""))).status);verifyNoInteractions(repository);}
    @Test void exactTargetReadRequiresAuthenticatedSession(){var r=request("/email/bindings/"+actor,"");r.setUserPrincipal(null);assertEquals(401,assertThrows(DomainException.class,()->controller.targetBinding(actor,r)).status);verifyNoInteractions(repository);}
}
