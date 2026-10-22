package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.notification.DeliveryReceiptState.*;
import cn.mop.persistence.DeliveryReceiptSnapshotCodec.Scope;
import cn.mop.persistence.DeliveryReceiptJournal.Command;
import cn.mop.persistence.PostgresDeliveryReceiptRepository.*;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.time.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DeliveryReceiptRepositoryTest {
 final UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID();final Access access=new Access(tenant,actor,1);final Scope scope=new Scope(tenant,UUID.randomUUID(),new Deduplication(UUID.randomUUID(),UUID.randomUUID(),Channel.WEBHOOK,UUID.randomUUID(),"OPENED"));final Instant at=Instant.parse("2026-10-21T00:00:00Z");
 @Test void callbacksCannotBeOmitted(){var source=mock(DataSource.class);assertThrows(NullPointerException.class,()->new PostgresDeliveryReceiptRepository(source,null,(c,a,s,o)->{}));assertThrows(NullPointerException.class,()->new PostgresDeliveryReceiptRepository(source,(c,a,s,o)->{},null));}
 @Test void crossTenantRejectedBeforeOpeningConnection()throws Exception{var source=mock(DataSource.class);var repo=new PostgresDeliveryReceiptRepository(source,(c,a,s,o)->{},(c,a,s,o)->{});assertEquals(404,assertThrows(DomainException.class,()->repo.current(new Access(UUID.randomUUID(),actor,1),scope)).status);verifyNoInteractions(source);}
 @Test void invalidTraceAndTimeRejectedBeforeOpeningConnection(){var source=mock(DataSource.class);var repo=new PostgresDeliveryReceiptRepository(source,(c,a,s,o)->{},(c,a,s,o)->{});for(String trace:List.of("","secret\n","a".repeat(129)))assertThrows(DomainException.class,()->repo.queue(access,scope,UUID.randomUUID(),at,trace));assertThrows(DomainException.class,()->repo.queue(access,scope,UUID.randomUUID(),null,"trace"));verifyNoInteractions(source);}
 @Test void commandActorAndPermissionMustMatchCurrentAccess(){var source=mock(DataSource.class);var repo=new PostgresDeliveryReceiptRepository(source,(c,a,s,o)->{},(c,a,s,o)->{});for(var c:List.of(new Command(0,UUID.randomUUID(),UUID.randomUUID(),1,"trace",new Started(UUID.randomUUID(),at)),new Command(0,UUID.randomUUID(),actor,2,"trace",new Started(UUID.randomUUID(),at))))assertEquals(400,assertThrows(DomainException.class,()->repo.append(access,scope,c)).status);verifyNoInteractions(source);}
 @Test void accessVersionIsBounded(){for(long v:new long[]{0,-1,9_007_199_254_740_992L})assertThrows(DomainException.class,()->new Access(tenant,actor,v));}
 @Test void connectionFailureIsSafeAndHasNoSecretCause()throws Exception{var source=mock(DataSource.class);when(source.getConnection()).thenThrow(new java.sql.SQLException("SYNTHETIC_PRIVATE_PASSWORD"));var repo=new PostgresDeliveryReceiptRepository(source,(c,a,s,o)->{},(c,a,s,o)->{});var e=assertThrows(DomainException.class,()->repo.current(access,scope));assertEquals(503,e.status);assertNull(e.getCause());assertFalse(e.getMessage().contains("PRIVATE"));}
 @Test void authorityDenialRollsBackClosesAndDoesNotReadRows()throws Exception{var source=mock(DataSource.class);var c=mock(java.sql.Connection.class);when(source.getConnection()).thenReturn(c);var repo=new PostgresDeliveryReceiptRepository(source,(connection,a,s,o)->{throw new DomainException(403,"SECRET_CODE","SYNTHETIC_PRIVATE_PASSWORD");},(connection,a,s,o)->{});var e=assertThrows(DomainException.class,()->repo.current(access,scope));assertEquals(403,e.status);assertEquals("DELIVERY_JOURNAL_ACCESS_DENIED",e.code);assertNull(e.getCause());verify(c).rollback();verify(c).close();verify(c,never()).prepareStatement(anyString());verify(c,never()).commit();}
}
