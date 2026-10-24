package cn.mop.api;
import cn.mop.domain.operations.*;
import cn.mop.domain.sla.SlaAvailabilityAndCompensation;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OperationalLatencyStatisticsTest {
 @Test void zeroSamplesStayUnknownNotZero(){var r=OperationalLatencyStatistics.calculate(List.of());assertEquals(0,r.sampleCount());assertNull(r.meanMillis());assertNull(r.medianMillis());assertNull(r.p95Millis());}
 @Test void evenMedianAndNearestRankP95UseExactIntegerArithmetic(){var r=OperationalLatencyStatistics.calculate(List.of(BigInteger.ZERO,BigInteger.valueOf(1001)));assertEquals(new BigDecimal("500.5"),r.meanMillis());assertEquals(new BigDecimal("500.5"),r.medianMillis());assertEquals(BigInteger.valueOf(1001),r.p95Millis());}
 @Test void largeSecondsDoNotOverflowTheLegacySlaAccumulator(){var r=SlaAvailabilityAndCompensation.computeLatency(List.of(Long.MAX_VALUE,Long.MAX_VALUE),0);assertEquals(Long.MAX_VALUE,r.meanSec());assertEquals(Long.MAX_VALUE,r.p50Sec());assertEquals(Long.MAX_VALUE,r.p95Sec());}
 @Test void existingSecondsPresentationAndExplicitComparisonUseTheSameMedian(){var seconds=SlaAvailabilityAndCompensation.computeLatency(List.of(1L,2L),3);assertEquals(2L,seconds.meanSec());assertEquals(2L,seconds.p50Sec());assertEquals(3,seconds.unrecoveredCount());var values=List.of(BigInteger.valueOf(1000),BigInteger.valueOf(2000));assertEquals(ExplicitOperationalMetrics.medianElapsedMillis(values).sampleMedianMillis(),OperationalLatencyStatistics.calculate(values).medianMillis());}
 @Test void actualZeroDurationIsNotMissing(){var r=OperationalLatencyStatistics.calculate(List.of(BigInteger.ZERO));assertEquals(1,r.sampleCount());assertEquals(BigDecimal.ZERO,r.meanMillis());}
 @Test void negativeNullAndOversizedCohortsFailClosed(){assertThrows(IllegalArgumentException.class,()->OperationalLatencyStatistics.calculate(List.of(BigInteger.valueOf(-1))));assertThrows(IllegalArgumentException.class,()->OperationalLatencyStatistics.calculate(Arrays.asList((BigInteger)null)));assertThrows(IllegalArgumentException.class,()->OperationalLatencyStatistics.calculate(Collections.nCopies(10001,BigInteger.ZERO)));}
}
