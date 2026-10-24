package cn.mop.api;
import cn.mop.domain.operations.*;
import cn.mop.domain.operations.EventOperationalMetricsReader.*;
import java.time.*;
import java.util.*;
import java.math.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EventOperationalMetricsTest {
 final Instant to=Instant.parse("2026-10-23T20:00:00Z"),from=to.minusSeconds(86400);final UUID tenant=UUID.randomUUID();
 Sample sample(int n,Instant ack,String state){return new Sample(UUID.fromString("79000000-0000-4000-8000-"+String.format("%012d",n)),UUID.randomUUID(),1,"Synthetic",Severity.HIGH,"PROBLEM",state,"NONE",to.minusSeconds(600),ack,to.minusSeconds(1));}
 @Test void calculatesFirstEventAcknowledgmentNotTicketCreationTime(){var r=EventOperationalMetrics.calculate(tenant,from,to,1,List.of(sample(1,to.minusSeconds(300),"ACKNOWLEDGED")));assertEquals("CALCULATED",r.status());assertEquals(new BigDecimal("300000"),r.statistics().meanMillis());assertEquals(1L,r.sampleCount());}
 @Test void unacknowledgedCountStaysSeparateWithoutInventingZeroLatency(){var r=EventOperationalMetrics.calculate(tenant,from,to,2,List.of(sample(1,to.minusSeconds(300),"ACKNOWLEDGED"),sample(2,null,"NEW")));assertEquals(1L,r.unacknowledgedCount());assertEquals(1L,r.sampleCount());assertEquals(new BigDecimal("300000"),r.statistics().meanMillis());}
 @Test void zeroAcknowledgedSamplesReturnNulls(){var r=EventOperationalMetrics.calculate(tenant,from,to,1,List.of(sample(1,null,"NEW")));assertEquals("NO_ACKNOWLEDGED_SAMPLES",r.status());assertNull(r.statistics().meanMillis());assertEquals(0L,r.sampleCount());}
 @Test void invalidOrMissingAcknowledgmentInvalidatesAggregateRatherThanDroppingBadRecords(){for(var bad:List.of(sample(2,to.plusSeconds(1),"ACKNOWLEDGED"),sample(2,to.minusSeconds(601),"ACKNOWLEDGED"),sample(2,null,"IN_PROGRESS"))){var r=EventOperationalMetrics.calculate(tenant,from,to,2,List.of(sample(1,to.minusSeconds(300),"ACKNOWLEDGED"),bad));assertEquals("INVALID_SOURCE_TIMESTAMPS",r.status());assertEquals(1L,r.invalidCount());assertNull(r.statistics());}}
 @Test void budgetExceededDoesNotPublishPartialStatistics(){var r=EventOperationalMetrics.calculate(tenant,from,to,10001,null);assertEquals("SAMPLE_BUDGET_EXCEEDED",r.status());assertNull(r.sampleCount());assertNull(r.statistics());assertNull(r.sourceManifestSha256());}
 @Test void incompleteAndDuplicateCohortsAreRejected(){var v=sample(1,null,"NEW");assertThrows(IllegalArgumentException.class,()->EventOperationalMetrics.calculate(tenant,from,to,2,List.of(v)));assertThrows(IllegalArgumentException.class,()->EventOperationalMetrics.calculate(tenant,from,to,2,List.of(v,v)));}
 @Test void sourceManifestIsOrderIndependentButBindsVersionsAndTimeWindow(){var a=sample(1,null,"NEW");var b=sample(2,null,"NEW");assertEquals(EventOperationalMetrics.calculate(tenant,from,to,2,List.of(a,b)).sourceManifestSha256(),EventOperationalMetrics.calculate(tenant,from,to,2,List.of(b,a)).sourceManifestSha256());assertNotEquals(EventOperationalMetrics.calculate(tenant,from,to,2,List.of(a,b)).sourceManifestSha256(),EventOperationalMetrics.calculate(tenant,from.minusSeconds(1),to,2,List.of(a,b)).sourceManifestSha256());}
}
