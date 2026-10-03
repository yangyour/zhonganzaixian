package cn.mop.api;
import cn.mop.domain.DomainException;
import cn.mop.domain.assetmetadata.AssetDateFilter;
import cn.mop.domain.assetmetadata.AssetDateFilter.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class AssetDateFilterTest {
    final LocalDate day=LocalDate.of(2026,10,3);
    AssetDateFilter f(Window w){return new AssetDateFilter(Field.WARRANTY_EXPIRY,w,day);}
    @Test void unknownMatchesOnlyNull(){assertTrue(f(Window.UNKNOWN).matches(null));assertFalse(f(Window.UNKNOWN).matches(day));}
    @Test void pastExcludesTodayAndUnknown(){assertTrue(f(Window.PAST_DATE).matches(day.minusDays(1)));assertFalse(f(Window.PAST_DATE).matches(day));assertFalse(f(Window.PAST_DATE).matches(null));}
    @Test void thirtyIncludesTodayAndBoundaryButNotPastOrThirtyOne(){var f=f(Window.WITHIN_30_DAYS);assertTrue(f.matches(day));assertTrue(f.matches(day.plusDays(29)));assertTrue(f.matches(day.plusDays(30)));assertFalse(f.matches(day.minusDays(1)));assertFalse(f.matches(day.plusDays(31)));assertFalse(f.matches(null));}
    @Test void ninetyExcludesNinetyOne(){assertTrue(f(Window.WITHIN_90_DAYS).matches(day.plusDays(90)));assertFalse(f(Window.WITHIN_90_DAYS).matches(day.plusDays(91)));}
    @Test void quarterIncludesEarlierSameQuarterButNotPreviousOrNext(){var f=f(Window.REFERENCE_QUARTER);assertEquals(LocalDate.of(2026,10,1),f.lowerInclusive());assertEquals(LocalDate.of(2026,12,31),f.upperInclusive());assertTrue(f.matches(LocalDate.of(2026,10,1)));assertFalse(f.matches(LocalDate.of(2026,9,30)));assertFalse(f.matches(LocalDate.of(2027,1,1)));}
    @Test void maxYearIsClampedAndQuarterHasNoOverflow(){var f=new AssetDateFilter(Field.OVERDUE_SERVICE_DATE,Window.WITHIN_90_DAYS,LocalDate.of(9999,12,1));assertEquals(LocalDate.of(9999,12,31),f.upperInclusive());assertTrue(f.matches(LocalDate.of(9999,12,31)));assertEquals(LocalDate.of(9999,12,31),new AssetDateFilter(Field.WARRANTY_EXPIRY,Window.REFERENCE_QUARTER,LocalDate.of(9999,12,31)).upperInclusive());}
    @Test void leapYearAndYearCrossingKeepCalendarBoundaries(){var f=new AssetDateFilter(Field.WARRANTY_EXPIRY,Window.WITHIN_30_DAYS,LocalDate.of(2024,2,1));assertEquals(LocalDate.of(2024,3,2),f.upperInclusive());assertEquals(LocalDate.of(2027,1,30),new AssetDateFilter(Field.WARRANTY_EXPIRY,Window.WITHIN_30_DAYS,LocalDate.of(2026,12,31)).upperInclusive());}
    @Test void explicitInputsAndSupportedYearRequired(){assertThrows(DomainException.class,()->new AssetDateFilter(null,Window.UNKNOWN,day));assertThrows(DomainException.class,()->new AssetDateFilter(Field.WARRANTY_EXPIRY,null,day));assertThrows(DomainException.class,()->new AssetDateFilter(Field.WARRANTY_EXPIRY,Window.UNKNOWN,null));assertThrows(DomainException.class,()->new AssetDateFilter(Field.WARRANTY_EXPIRY,Window.UNKNOWN,LocalDate.of(0,1,1)));assertThrows(DomainException.class,()->f(Window.UNKNOWN).matches(LocalDate.of(10000,1,1)));}
}
