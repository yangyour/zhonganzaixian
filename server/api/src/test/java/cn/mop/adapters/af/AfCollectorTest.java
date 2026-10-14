package cn.mop.adapters.af;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AfCollectorTest {
    final Clock clock=Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"),ZoneOffset.UTC);
    final Map<String,String> ids=Map.of("cpu","1","memory","2","state","3");
    @Test void validSourceValuesArePublishedWithSharedReceiptTime(){var s=AfCollectorMain.collect(()->Map.of("cpu","12.50","memory","58"),clock);assertEquals("OK",s.state());assertEquals(clock.instant(),s.receivedAt());assertEquals(Map.of("1","12.50","2","58","3","OK"),AfCollectorMain.values(s,ids));}
    @Test void failedCollectionPublishesOnlyStatusAndNeverZeros(){for(String reason:List.of("PASSWORD_EXPIRED","ACCESS_DENIED","AUTHENTICATION_REJECTED")){var s=AfCollectorMain.collect(()->{throw new AfClient.Failure(reason);},clock);assertTrue(s.metrics().isEmpty());assertEquals(Map.of("3",reason),AfCollectorMain.values(s,ids));}}
    @Test void partialOrInvalidMetricsCannotProduceOk(){for(var data:List.of(Map.of("cpu","12"),Map.of("cpu","NaN","memory","58"),Map.of("cpu","101","memory","58"),Map.of("cpu","12","memory","-1"))){var s=AfCollectorMain.collect(()->data,clock);assertNotEquals("OK",s.state());assertEquals(1,AfCollectorMain.values(s,ids).size());}}
    @Test void providerSecretsCannotEscapeFailureStatus(){var s=AfCollectorMain.collect(()->{throw new IllegalStateException("PRIVATE_SOURCE_SECRET");},clock);assertEquals("SOURCE_UNAVAILABLE",s.state());assertFalse(s.toString().contains("PRIVATE"));}
}
