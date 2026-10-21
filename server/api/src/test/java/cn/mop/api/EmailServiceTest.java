package cn.mop.api;

import cn.mop.adapters.email.*;
import cn.mop.persistence.PostgresEmailRepository;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailServiceTest {
    SmtpMailClient client;PostgresEmailRepository repository;EmailService service;
    final String sender="sender@example.test",recipient="recipient@example.test";
    @BeforeEach void setup(){client=mock(SmtpMailClient.class);repository=mock(PostgresEmailRepository.class);when(client.settings()).thenReturn(new SmtpSettings("smtp.example.test",sender,"synthetic-secret",sender,"MOP",recipient,Set.of(recipient)));service=new EmailService(client,repository,"tenant",true);}
    PostgresEmailRepository.Claim claim(boolean allowed){return new PostgresEmailRepository.Claim("id","tenant","actor",sender,recipient,"subject","body","attempt",allowed);}
    @Test void databaseFailureIsUnreadyBeforeSmtp(){when(repository.readinessProblems()).thenReturn(List.of("EMAIL_SCHEMA_OR_ACCESS_UNAVAILABLE"));assertEquals(false,service.check().get("ready"));verify(client,never()).check();}
    @Test void authenticationFailureIsUnreadyWithSafeReason(){doThrow(new SmtpMailClient.Failure("EMAIL_AUTHENTICATION_REJECTED",false,null)).when(client).check();assertEquals(false,service.check().get("ready"));assertEquals("EMAIL_AUTHENTICATION_REJECTED",service.check().get("reason"));}
    @Test void authCheckDoesNotSendAndStatusDoesNotClaimDelivery(){assertEquals(true,service.check().get("ready"));assertEquals(false,service.status().get("receipt_query_supported"));assertEquals(false,service.status().get("automatic_notifications_enabled"));verify(client,never()).send(anyString(),anyString(),anyString(),anyString());}
    @Test void workerRetainsUnknownAndMakesOneAttempt(){var claim=claim(true);when(repository.claim("tenant",sender)).thenReturn(Optional.of(claim),Optional.empty());when(client.send(anyString(),anyString(),anyString(),anyString())).thenThrow(new SmtpMailClient.Failure("EMAIL_SUBMISSION_OUTCOME_UNKNOWN",true,null));service.dispatch();verify(repository).finish(claim,"UNKNOWN",null,"EMAIL_SUBMISSION_OUTCOME_UNKNOWN",null);verify(client,times(1)).send("id",recipient,"subject","body");}
    @Test void revokedBindingCancelsBeforeNetwork(){var claim=claim(false);when(repository.claim("tenant",sender)).thenReturn(Optional.of(claim),Optional.empty());service.dispatch();verify(repository).finish(claim,"CANCELLED",null,"EMAIL_AUTHORIZATION_OR_BINDING_CHANGED",null);verify(client,never()).send(anyString(),anyString(),anyString(),anyString());}
    @Test void acceptedIsPersistedAndNotDelivered(){var claim=claim(true);when(repository.claim("tenant",sender)).thenReturn(Optional.of(claim),Optional.empty());when(client.send(anyString(),anyString(),anyString(),anyString())).thenReturn(new SmtpMailClient.Accepted("<mop.id@example.test>",250));service.dispatch();verify(repository).finish(claim,"ACCEPTED","<mop.id@example.test>",null,250);}
    @Test void disabledWorkerCannotClaimOrSilentlyQueue(){new EmailService(client,repository,"tenant",false).dispatch();verifyNoInteractions(repository);assertThrows(cn.mop.domain.DomainException.class,()->new EmailService(client,repository,"tenant",false).requireDispatch());}
    @Test void removedAddressCanStillBeDisabled(){assertEquals("old@example.test",service.bindingAddress("old@example.test",false));assertThrows(cn.mop.domain.DomainException.class,()->service.bindingAddress("old@example.test",true));}
}
