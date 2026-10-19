package cn.mop.api;

import cn.mop.adapters.wecom.WeComClient;
import cn.mop.domain.DomainException;
import cn.mop.persistence.PostgresWeComRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WeComServiceTest {
    final WeComClient client=mock(WeComClient.class);
    final PostgresWeComRepository repository=mock(PostgresWeComRepository.class);
    WeComService service(boolean login,boolean send){when(client.corpId()).thenReturn("wwsynthetic12345678");when(client.agentId()).thenReturn(1000001);return new WeComService(client,repository,"55000000-0000-4000-8000-000000000001","Synthetic",login,"https://mop.example.test/api/v1/auth/wecom/callback",send);}
    @Test void startCreatesIndependentRandomStateAndBrowserSecretAndPersistsOnlyHashes(){var s=service(true,false);var first=s.startLogin("web");var second=s.startLogin("work");assertNotEquals(first.state(),first.browserCookie());assertNotEquals(first.state(),second.state());assertTrue(first.authorizationUrl().startsWith("https://login.work.weixin.qq.com/"));assertTrue(second.authorizationUrl().startsWith("https://open.weixin.qq.com/"));verify(repository).createLoginState(eq(WeComService.hash(first.state())),eq(WeComService.hash(first.browserCookie())),anyString(),anyString(),anyInt());}
    @Test void disabledLoginAndMalformedCorrelationDoNotAccessProvider(){var s=service(false,false);assertThrows(DomainException.class,()->s.startLogin("web"));assertFalse(s.consumeLogin(null,null));verifyNoInteractions(repository);}
    @Test void callbackMustBeFixedAndHttpRequiresExplicitOptIn(){for(String url:List.of("https://user:pass@mop.example.test/api/v1/auth/wecom/callback","https://mop.example.test/elsewhere","https://mop.example.test/api/v1/auth/wecom/callback?redirect=https://evil.test","http://mop.example.test/api/v1/auth/wecom/callback"))assertThrows(IllegalArgumentException.class,()->WeComService.validateCallback(url,false));assertDoesNotThrow(()->WeComService.validateCallback("http://mop.example.test/api/v1/auth/wecom/callback",true));}
    @Test void databaseFailureMakesReadinessFalseWithoutProviderCall(){var s=service(false,false);when(repository.readinessProblems()).thenReturn(List.of("WECOM_SCHEMA_OR_ACCESS_UNAVAILABLE"));assertEquals(false,s.check().get("ready"));verify(client,never()).application();}
    @Test void providerFailureMakesReadinessFalseWithSafeReason(){var s=service(false,false);when(repository.readinessProblems()).thenReturn(List.of());when(client.application()).thenThrow(new WeComClient.Failure("WECOM_TRUSTED_IP_REQUIRED",60020,false));assertEquals("WECOM_TRUSTED_IP_REQUIRED",s.check().get("reason"));}
    @Test void workerPersistsAcceptanceOnlyAndNeverRetriesUnknown(){var s=service(false,true);var claim=new PostgresWeComRepository.Claim("id","tenant","actor","Synthetic","message","attempt",true);when(repository.claim(anyString(),anyString(),anyInt())).thenReturn(Optional.of(claim),Optional.empty());when(client.application()).thenReturn(new WeComClient.Application(1000001,"Synthetic",false));when(client.sendText(anyString(),anyString())).thenThrow(new WeComClient.Failure("WECOM_NETWORK_OR_RESPONSE_UNAVAILABLE",null,true));s.dispatch();verify(repository).finish(claim,"UNKNOWN",null,"WECOM_NETWORK_OR_RESPONSE_UNAVAILABLE",null);verify(client,times(1)).sendText("Synthetic","message");}
    @Test void revocationCancelsBeforeExternalSend(){var s=service(false,true);var claim=new PostgresWeComRepository.Claim("id","tenant","actor","Synthetic","message","attempt",false);when(repository.claim(anyString(),anyString(),anyInt())).thenReturn(Optional.of(claim),Optional.empty());s.dispatch();verify(repository).finish(claim,"CANCELLED",null,"AUTHORIZATION_OR_BINDING_CHANGED",null);verify(client,never()).sendText(anyString(),anyString());}
    @Test void disabledWorkerDoesNotTouchQueue(){service(false,false).dispatch();verify(repository,never()).claim(anyString(),anyString(),anyInt());}
}
