package cn.mop.api;
import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.ObservationWindowAnalysis;
import cn.mop.domain.monitoring.ObservationWindowAnalysis.*;
import cn.mop.domain.monitoring.ThresholdConditionAnalysis.Condition;
import cn.mop.domain.monitoring.ThresholdConditionAnalysis.Comparison;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ObservationWindowAnalysisTest {
    static final Instant T=Instant.parse("2026-10-21T00:00:00Z");
    static BigDecimal n(String value){return new BigDecimal(value);}
    static Sample sample(long millis,String value){return new Sample(T.plusMillis(millis),SampleState.KNOWN,n(value),"%");}
    static Sample unknown(long millis,SampleState state){return new Sample(T.plusMillis(millis),state,null,null);}
    static Condition condition(Comparison c,String value){return new Condition(c,n(value),"%");}
    static ObservationWindowAnalysis.Input input(List<Sample> samples,String hold,String gap,long observedMillis){return new ObservationWindowAnalysis.Input(condition(Comparison.LE,"5"),hold==null?null:n(hold),gap==null?null:n(gap),T.plusMillis(observedMillis),samples);}
    static Result run(List<Sample> samples,String hold,String gap,long at){return ObservationWindowAnalysis.analyze(input(samples,hold,gap,at));}
    @Test void exactHoldBoundaryUsesObservedSamples(){var r=run(List.of(sample(0,"5"),sample(60_000,"4"),sample(120_000,"3")),"120","60",120_000);assertEquals(Status.MATCHING_SPAN_REACHED,r.status());assertEquals(3,r.matchingSampleCount());assertEquals(0,r.observedSpanSeconds().compareTo(n("120")));assertEquals(T,r.matchingSpanStartedAt());}
    @Test void submillisecondThresholdIsNotRoundedDown(){var r=run(List.of(sample(0,"1"),sample(1000,"1")),"1.00000000000000001","1",1000);assertEquals(Status.MATCHING_SPAN_NOT_REACHED,r.status());}
    @Test void tailToObservationDoesNotInventAHealthySample(){var r=run(List.of(sample(0,"1"),sample(1000,"1")),"2","10",2000);assertEquals(Status.MATCHING_SPAN_NOT_REACHED,r.status());assertEquals(0,r.observedSpanSeconds().compareTo(n("1")));}
    @Test void gapBoundaryAllowsEqualityButOneMillisecondMoreRestarts(){assertEquals(Status.MATCHING_SPAN_REACHED,run(List.of(sample(0,"1"),sample(60_000,"1")),"60","60",60_000).status());var r=run(List.of(sample(0,"1"),sample(60_001,"1")),"60","60",60_001);assertEquals(Status.MATCHING_SPAN_NOT_REACHED,r.status());assertEquals(1,r.matchingSampleCount());assertEquals(T.plusMillis(60_001),r.matchingSpanStartedAt());}
    @Test void unknownOrCollectorErrorRestartsMatchingSpan(){for(var state:List.of(SampleState.UNKNOWN,SampleState.COLLECTOR_ERROR)){var r=run(List.of(sample(0,"1"),unknown(30_000,state),sample(60_000,"1")),"60","60",60_000);assertEquals(Status.MATCHING_SPAN_NOT_REACHED,r.status());assertEquals(1,r.matchingSampleCount());}}
    @Test void latestUnknownDoesNotBecomeZeroOrRecovered(){var r=run(List.of(sample(0,"1"),unknown(1000,SampleState.UNKNOWN)),"0","60",1000);assertEquals(Status.UNKNOWN_LATEST,r.status());assertNull(r.candidateSpanReached());assertNull(r.observedSpanSeconds());}
    @Test void staleLatestRemainsUnknownRatherThanUsingOlderGoodRun(){var r=run(List.of(sample(0,"1"),sample(1000,"1")),"1","1",2001);assertEquals(Status.STALE_LATEST,r.status());assertNull(r.candidateSpanReached());assertNull(r.matchingSpanStartedAt());}
    @Test void nonmatchingValueBreaksRun(){var r=run(List.of(sample(0,"1"),sample(1000,"6")),"0","1",1000);assertEquals(Status.NO_CURRENT_MATCH,r.status());assertFalse(r.candidateSpanReached());assertEquals(0,r.matchingSampleCount());}
    @Test void comparisonsAreExactAndNeverInverted(){for(var c:Comparison.values()){var r=ObservationWindowAnalysis.analyze(new ObservationWindowAnalysis.Input(condition(c,"1.00000000000000001"),BigDecimal.ZERO,n("1"),T,List.of(sample(0,"1"))));assertEquals(c==Comparison.LT||c==Comparison.LE,r.candidateSpanReached());}}
    @Test void equalityDistinguishesInclusiveAndStrictOperators(){for(var c:Comparison.values()){var r=ObservationWindowAnalysis.analyze(new ObservationWindowAnalysis.Input(condition(c,"1"),BigDecimal.ZERO,n("1"),T,List.of(sample(0,"1.000"))));assertEquals(c==Comparison.GE||c==Comparison.LE,r.candidateSpanReached());}}
    @Test void missingParametersStayPendingNotDefaulted(){var r=run(List.of(sample(0,"1")),null,null,0);assertEquals(Status.CONFIG_PENDING,r.status());assertEquals(List.of(Reason.HOLD_MISSING,Reason.MAX_GAP_MISSING),r.reasons());assertNull(r.candidateSpanReached());}
    @Test void incompleteConditionIsNotInferredFromSamples(){var r=ObservationWindowAnalysis.analyze(new ObservationWindowAnalysis.Input(null,BigDecimal.ZERO,n("60"),T,List.of(sample(0,"1"))));assertEquals(Status.CONFIG_PENDING,r.status());assertEquals(List.of(Reason.CONDITION_INCOMPLETE),r.reasons());}
    @Test void negativeDurationsAndUnitMismatchConflictWithoutConversion(){assertEquals(Status.CONFLICT,run(List.of(sample(0,"1")),"-1","60",0).status());assertEquals(Status.CONFLICT,run(List.of(sample(0,"1")),"0","-1",0).status());var r=ObservationWindowAnalysis.analyze(new ObservationWindowAnalysis.Input(condition(Comparison.LE,"5"),BigDecimal.ZERO,n("60"),T,List.of(new Sample(T,SampleState.KNOWN,n("1"),"ratio"))));assertEquals(List.of(Reason.UNIT_MISMATCH),r.reasons());assertFalse(r.unitsConverted());}
    @Test void emptySamplesDoNotMeanHealthyOrZero(){var r=run(List.of(),"0","60",0);assertEquals(Status.NO_SAMPLES,r.status());assertNull(r.matchingSampleCount());assertNull(r.observedSpanSeconds());assertNull(r.candidateSpanReached());}
    @Test void explicitZeroHoldAndGapAreNotMissing(){assertEquals(Status.MATCHING_SPAN_REACHED,run(List.of(sample(0,"1")),"0","0",0).status());assertEquals(Status.STALE_LATEST,run(List.of(sample(0,"1")),"0","0",1).status());}
    @Test void duplicatesOutOfOrderAndFutureSamplesAreRejected(){for(var rows:List.of(List.of(sample(0,"1"),sample(0,"1")),List.of(sample(1000,"1"),sample(0,"1")),List.of(sample(2000,"1"))))assertThrows(DomainException.class,()->input(rows,"1","1",1000));}
    @Test void unknownCannotCarryAValueAndKnownCannotOmitOne(){assertThrows(DomainException.class,()->new Sample(T,SampleState.UNKNOWN,BigDecimal.ZERO,null));assertThrows(DomainException.class,()->new Sample(T,SampleState.COLLECTOR_ERROR,null,"\u0000"));assertThrows(DomainException.class,()->new Sample(T,SampleState.KNOWN,null,"%"));}
    @Test void clockBoundsRejectNanosecondsAndYearZero(){assertThrows(DomainException.class,()->new Sample(T.plusNanos(1),SampleState.KNOWN,BigDecimal.ZERO,"%"));assertThrows(DomainException.class,()->new Sample(Instant.parse("0000-01-01T00:00:00Z"),SampleState.KNOWN,BigDecimal.ZERO,"%"));}
    @Test void inputAndReasonsAreImmutable(){var list=new ArrayList<>(List.of(sample(0,"1")));var in=input(list,null,null,0);list.clear();assertEquals(1,in.samples().size());assertThrows(UnsupportedOperationException.class,()->in.samples().clear());assertThrows(UnsupportedOperationException.class,()->ObservationWindowAnalysis.analyze(in).reasons().clear());}
    @Test void technicalSampleLimitRejectsRatherThanTruncates(){var rows=new ArrayList<Sample>();for(int i=0;i<1001;i++)rows.add(sample(i,"1"));assertThrows(DomainException.class,()->input(rows,"0","60",1001));rows.remove(rows.size()-1);assertEquals(1000,input(rows,"0","60",1001).samples().size());}
    @Test void hugeExplicitHoldDoesNotOverflowOrWrap(){var r=run(List.of(sample(0,"1"),sample(1000,"1")),"9999999999999999999999999999999999999999","1",1000);assertEquals(Status.MATCHING_SPAN_NOT_REACHED,r.status());}
    @Test void unknownMayRetainKnownUnitMetadataWithoutInventingAValue(){var r=run(List.of(new Sample(T,SampleState.UNKNOWN,null,"%")),"0","60",0);assertEquals(Status.UNKNOWN_LATEST,r.status());assertNull(r.candidateSpanReached());}
    @Test void nullInputSampleAndClockAreRejectedAsValidationErrors(){assertThrows(DomainException.class,()->ObservationWindowAnalysis.analyze(null));assertThrows(DomainException.class,()->input(Arrays.asList((Sample)null),"0","60",0));assertThrows(DomainException.class,()->new ObservationWindowAnalysis.Input(null,null,null,null,List.of()));}
    @Test void reachedCandidateNeverAuthorizesRecoveryPublicationOrWrites(){var r=run(List.of(sample(0,"1")),"0","60",0);assertTrue(r.candidateSpanReached());assertFalse(r.recoveryConfirmed());assertFalse(r.eventStateUpdated());assertFalse(r.monitoringApplied());assertFalse(r.publishable());assertFalse(r.writesPerformed());assertNull(r.effectiveRule());assertEquals("UNVERIFIED",r.customerConfirmation());assertEquals("OBSERVED_POINTS_ONLY_NOT_CONTINUOUS_PHYSICAL_PROOF",r.continuityClaim());}
}
