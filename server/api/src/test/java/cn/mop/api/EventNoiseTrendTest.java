package cn.mop.api;
import cn.mop.domain.monitoring.EventNoiseTrendReader;
import cn.mop.domain.monitoring.EventNoiseTrendReader.*;
import cn.mop.domain.monitoring.MonitoringEventPipeline.NoiseLayer;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EventNoiseTrendTest {
 final Access access=new Access(UUID.randomUUID(),UUID.randomUUID(),1);final Instant at=Instant.parse("2026-10-23T20:00:00.123Z");
 @Test void allPresetsHaveContiguousSharedBucketsAndExactWindow(){for(var w:Window.values()){var result=EventNoiseTrendReader.assemble(access,w,at,List.of());assertEquals(w.buckets,result.buckets().size());assertEquals(at.minusSeconds(w.durationSeconds()),result.buckets().get(0).from());assertEquals(at,result.buckets().get(w.buckets-1).to());assertTrue(result.buckets().stream().allMatch(b->b.rawCount()==0&&b.afterFingerprintMergeCount()==0));}}
 @Test void fingerprintMergesAreSubtractedOnlyOnceAndLayersStaySeparate(){var result=EventNoiseTrendReader.assemble(access,Window.HOURS_24,at,List.of(new Aggregated(0,NoiseLayer.RAW_INTAKE,10),new Aggregated(0,NoiseLayer.MERGE,4),new Aggregated(0,NoiseLayer.DEPENDENCY,3),new Aggregated(0,NoiseLayer.MAINTENANCE,2),new Aggregated(0,NoiseLayer.EFFECTIVE_ALERT,1)));assertEquals(6,result.buckets().get(0).afterFingerprintMergeCount());assertEquals(3,result.totals().get(NoiseLayer.DEPENDENCY));assertEquals(2,result.totals().get(NoiseLayer.MAINTENANCE));}
 @Test void effectiveAlertsCannotBecomeFinalNotificationsOrNoiseReduction(){var result=EventNoiseTrendReader.assemble(access,Window.HOURS_24,at,List.of(new Aggregated(0,NoiseLayer.RAW_INTAKE,10),new Aggregated(0,NoiseLayer.EFFECTIVE_ALERT,1)));var dto=EventNoiseTrendController.dto(result);assertNull(dto.get("final_notification_count"));assertNull(dto.get("noise_reduction_percent"));assertEquals(false,dto.get("final_notifications_verified"));assertEquals(false,dto.get("collection_freshness_verified"));}
 @Test void contradictoryDuplicateOrOutOfWindowAggregatesFail(){for(var rows:List.of(List.of(new Aggregated(0,NoiseLayer.MERGE,1)),List.of(new Aggregated(24,NoiseLayer.RAW_INTAKE,1)),List.of(new Aggregated(0,NoiseLayer.RAW_INTAKE,0)),List.of(new Aggregated(0,NoiseLayer.RAW_INTAKE,1),new Aggregated(0,NoiseLayer.RAW_INTAKE,2))))assertThrows(IllegalArgumentException.class,()->EventNoiseTrendReader.assemble(access,Window.HOURS_24,at,rows));}
}
