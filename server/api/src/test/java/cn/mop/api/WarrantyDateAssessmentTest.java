package cn.mop.api;
import cn.mop.domain.DomainException;
import cn.mop.domain.warranty.WarrantyDateAssessment;
import cn.mop.domain.warranty.WarrantyDateAssessment.Position;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class WarrantyDateAssessmentTest {
    final LocalDate reference=LocalDate.of(2026,10,3);
    @Test void missingDatesRemainUnknownWithoutDefaultExpiredOrZero(){var a=WarrantyDateAssessment.assess(reference,null,null);for(var r:new WarrantyDateAssessment.DateResult[]{a.warrantyExpiry(),a.overdueServiceDate()}){assertNull(r.recordedDate());assertNull(r.daysUntil());assertEquals(Position.UNKNOWN,r.position());assertNull(r.within7Days());assertNull(r.within30Days());assertNull(r.within90Days());assertNull(r.inReferenceQuarter());}}
    @Test void pastTodayAndFutureAreDistinctWithoutAssumingCoverage(){assertEquals(Position.PAST_DATE,at(-1).position());assertEquals(-1L,at(-1).daysUntil());assertEquals(Position.DUE_TODAY,at(0).position());assertEquals(0L,at(0).daysUntil());assertEquals(Position.WITHIN_30_DAYS,at(1).position());assertEquals(Position.FUTURE_DATE,at(31).position());}
    @Test void sevenDayBoundaryIsInclusiveButPastNotUpcoming(){assertFalse(at(-1).within7Days());assertTrue(at(0).within7Days());assertTrue(at(7).within7Days());assertFalse(at(8).within7Days());}
    @Test void thirtyDayBoundaryMatchesTwentyNineExample(){assertTrue(at(29).within30Days());assertTrue(at(30).within30Days());assertFalse(at(31).within30Days());assertEquals(Position.WITHIN_30_DAYS,at(29).position());}
    @Test void ninetyDayBoundaryExcludesNinetyOne(){assertTrue(at(90).within90Days());assertFalse(at(91).within90Days());assertFalse(at(-90).within90Days());}
    @Test void leapYearDayIsCalendarArithmeticNotHours(){var a=WarrantyDateAssessment.assess(LocalDate.of(2024,2,28),LocalDate.of(2024,3,1),null);assertEquals(2L,a.warrantyExpiry().daysUntil());var b=WarrantyDateAssessment.assess(LocalDate.of(2025,2,28),LocalDate.of(2025,3,1),null);assertEquals(1L,b.warrantyExpiry().daysUntil());}
    @Test void quarterIsSameCalendarYearAndQuarterIncludingPastDates(){assertTrue(at(-2).inReferenceQuarter());assertFalse(at(-3).inReferenceQuarter());var a=WarrantyDateAssessment.assess(LocalDate.of(2026,12,31),LocalDate.of(2027,1,1),null);assertFalse(a.warrantyExpiry().inReferenceQuarter());assertEquals(1L,a.warrantyExpiry().daysUntil());}
    @Test void warrantyAndEndOfServiceRemainIndependent(){var a=WarrantyDateAssessment.assess(reference,reference.plusDays(91),reference.minusDays(1));assertEquals(Position.FUTURE_DATE,a.warrantyExpiry().position());assertEquals(Position.PAST_DATE,a.overdueServiceDate().position());}
    @Test void shanghaiMidnightUsesServerInstantAndNotUtcDay(){assertEquals(LocalDate.of(2026,10,2),WarrantyDateAssessment.referenceDate(Instant.parse("2026-10-02T15:59:59.999Z")));assertEquals(LocalDate.of(2026,10,3),WarrantyDateAssessment.referenceDate(Instant.parse("2026-10-02T16:00:00Z")));}
    @Test void explicitReferenceRequiredAndYearBoundsFailClosed(){assertThrows(DomainException.class,()->WarrantyDateAssessment.assess(null,null,null));assertThrows(DomainException.class,()->WarrantyDateAssessment.referenceDate(null));assertThrows(DomainException.class,()->WarrantyDateAssessment.referenceDate(Instant.MAX));assertThrows(DomainException.class,()->WarrantyDateAssessment.assess(reference,LocalDate.of(0,1,1),null));assertThrows(DomainException.class,()->WarrantyDateAssessment.assess(reference,null,LocalDate.of(10000,1,1)));}
    @Test void earliestLatestDatesHaveExactLongDifferences(){var a=WarrantyDateAssessment.assess(LocalDate.of(1,1,1),LocalDate.of(9999,12,31),null);assertEquals(3652058L,a.warrantyExpiry().daysUntil());assertFalse(a.warrantyExpiry().within90Days());}
    @Test void calculationDoesNotClaimCoverageHazardsOrDelivery(){var a=WarrantyDateAssessment.assess(reference,reference.plusDays(29),null);assertFalse(a.coverageVerified());assertEquals("NOT_REQUESTED",a.hazardCreationStatus());assertEquals("NOT_REQUESTED",a.notificationStatus());assertEquals("RECORDED_DATE_ASSESSMENT_ONLY",a.scope());assertEquals("Asia/Shanghai",a.timezone());}
    WarrantyDateAssessment.DateResult at(long days){return WarrantyDateAssessment.assess(reference,reference.plusDays(days),null).warrantyExpiry();}
}
