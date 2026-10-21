package cn.mop.domain.monitoring;

import cn.mop.domain.DomainException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static cn.mop.domain.monitoring.ThresholdConditionAnalysis.*;

/** Explicit candidate samples only. A matching observed span is not proof of continuous physical recovery. */
public final class ObservationWindowAnalysis {
    private ObservationWindowAnalysis() {}
    public static final int MAX_SAMPLES=1000;
    private static final Instant MIN=Instant.parse("0001-01-01T00:00:00Z"),MAX=Instant.parse("9999-12-31T23:59:59.999Z");
    public enum SampleState { KNOWN, UNKNOWN, COLLECTOR_ERROR }
    public enum Status { CONFIG_PENDING, CONFLICT, NO_SAMPLES, STALE_LATEST, UNKNOWN_LATEST, NO_CURRENT_MATCH, MATCHING_SPAN_NOT_REACHED, MATCHING_SPAN_REACHED }
    public enum Reason { CONDITION_INCOMPLETE, HOLD_MISSING, MAX_GAP_MISSING, NEGATIVE_HOLD, NEGATIVE_MAX_GAP, UNIT_MISMATCH }
    public record Sample(Instant recordedAt,SampleState state,BigDecimal value,String unit) {
        public Sample {
            time(recordedAt);if(state==null)throw invalid();
            try {if(state==SampleState.KNOWN){if(value==null||unit==null)throw invalid();new Condition(Comparison.GE,value,unit);}
            else {if(value!=null)throw invalid();if(unit!=null)new Condition(Comparison.GE,null,unit);}}catch(IllegalArgumentException failure){throw invalid();}
        }
    }
    public record Input(Condition condition,BigDecimal holdSeconds,BigDecimal maxGapSeconds,Instant observedAt,List<Sample> samples) {
        public Input {
            time(observedAt);new InputBounds(holdSeconds,maxGapSeconds);
            if(samples==null||samples.size()>MAX_SAMPLES||samples.stream().anyMatch(Objects::isNull))throw invalid();
            samples=List.copyOf(samples);Instant previous=null;
            for(Sample sample:samples){if(sample==null||sample.recordedAt().isAfter(observedAt)||previous!=null&&!sample.recordedAt().isAfter(previous))throw invalid();previous=sample.recordedAt();}
        }
    }
    /** Reuse the exact decimal resource bounds of the predicate diagnostic, without assigning default values. */
    private record InputBounds(BigDecimal hold,BigDecimal gap){InputBounds{try{new ThresholdConditionAnalysis.Input(null,null,null,null,hold,gap);}catch(IllegalArgumentException failure){throw invalid();}}}
    public record Result(Status status,List<Reason> reasons,Instant latestSampleAt,Instant matchingSpanStartedAt,
                         Integer matchingSampleCount,BigDecimal observedSpanSeconds,Boolean candidateSpanReached) {
        public Result {reasons=List.copyOf(reasons);}
        public String algorithm(){return "EXPLICIT_MATCHING_OBSERVED_SPAN_V1";}
        public String scope(){return "EXPLICIT_CANDIDATE_SAMPLES_ONLY";}
        public String continuityClaim(){return "OBSERVED_POINTS_ONLY_NOT_CONTINUOUS_PHYSICAL_PROOF";}
        public String customerConfirmation(){return "UNVERIFIED";}
        public String physicalRangeCheck(){return "NOT_RUN";}
        public boolean publishable(){return false;}
        public boolean monitoringApplied(){return false;}
        public boolean eventStateUpdated(){return false;}
        public boolean recoveryConfirmed(){return false;}
        public boolean writesPerformed(){return false;}
        public boolean unitsConverted(){return false;}
        public Object effectiveRule(){return null;}
    }
    public static Result analyze(Input input){
        if(input==null)throw invalid();Condition condition=input.condition();List<Reason> reasons=new ArrayList<>();
        boolean complete=condition!=null&&condition.comparison()!=null&&condition.threshold()!=null&&condition.unit()!=null;
        if(!complete)reasons.add(Reason.CONDITION_INCOMPLETE);
        if(input.holdSeconds()==null)reasons.add(Reason.HOLD_MISSING);else if(input.holdSeconds().signum()<0)reasons.add(Reason.NEGATIVE_HOLD);
        if(input.maxGapSeconds()==null)reasons.add(Reason.MAX_GAP_MISSING);else if(input.maxGapSeconds().signum()<0)reasons.add(Reason.NEGATIVE_MAX_GAP);
        if(complete&&input.samples().stream().anyMatch(s->s.state()==SampleState.KNOWN&&!condition.unit().equals(s.unit())))reasons.add(Reason.UNIT_MISMATCH);
        Instant latest=input.samples().isEmpty()?null:input.samples().get(input.samples().size()-1).recordedAt();
        if(!reasons.isEmpty()){boolean conflict=reasons.stream().anyMatch(r->r==Reason.NEGATIVE_HOLD||r==Reason.NEGATIVE_MAX_GAP||r==Reason.UNIT_MISMATCH);return new Result(conflict?Status.CONFLICT:Status.CONFIG_PENDING,reasons,latest,null,null,null,null);}
        if(latest==null)return new Result(Status.NO_SAMPLES,List.of(),null,null,null,null,null);
        Instant start=null,previous=null;int count=0;Boolean matching=null;
        for(Sample sample:input.samples()){
            if(previous!=null&&seconds(previous,sample.recordedAt()).compareTo(input.maxGapSeconds())>0){start=null;count=0;}
            if(sample.state()!=SampleState.KNOWN){start=null;count=0;matching=null;}
            else {int order=sample.value().compareTo(condition.threshold());matching=switch(condition.comparison()){case GT->order>0;case GE->order>=0;case LT->order<0;case LE->order<=0;};if(matching){if(start==null)start=sample.recordedAt();count++;}else{start=null;count=0;}}
            previous=sample.recordedAt();
        }
        // Never extend a matching span from the final sample to observedAt; no sample exists in that tail.
        if(seconds(latest,input.observedAt()).compareTo(input.maxGapSeconds())>0)return new Result(Status.STALE_LATEST,List.of(),latest,null,null,null,null);
        if(matching==null)return new Result(Status.UNKNOWN_LATEST,List.of(),latest,null,null,null,null);
        if(!matching)return new Result(Status.NO_CURRENT_MATCH,List.of(),latest,null,0,BigDecimal.ZERO,false);
        BigDecimal span=seconds(start,latest);boolean reached=span.compareTo(input.holdSeconds())>=0;
        return new Result(reached?Status.MATCHING_SPAN_REACHED:Status.MATCHING_SPAN_NOT_REACHED,List.of(),latest,start,count,span,reached);
    }
    private static BigDecimal seconds(Instant from,Instant to){return BigDecimal.valueOf(to.toEpochMilli()).subtract(BigDecimal.valueOf(from.toEpochMilli())).movePointLeft(3);}
    private static void time(Instant value){if(value==null||value.isBefore(MIN)||value.isAfter(MAX)||value.getNano()%1_000_000!=0)throw invalid();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","观察窗诊断须有明确毫秒UTC时刻、有界严格递增且不晚于观察时刻的样本；未知样本不能携带伪数值");}
}
