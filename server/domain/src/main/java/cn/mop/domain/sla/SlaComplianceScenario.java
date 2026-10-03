package cn.mop.domain.sla;

import cn.mop.domain.DomainException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** M5-19 exact comparisons over explicit frozen-input scenarios. No runtime authority, clock defaults or money. */
public final class SlaComplianceScenario {
    private SlaComplianceScenario(){}
    public static final int MAX_TICKETS=10000;
    public enum Priority { P1,P2,P3,P4 }
    public enum Exclusion { NONE,PENDING_APPROVAL,APPROVED,REJECTED,UNKNOWN }
    public enum Check { MET,NOT_MET,UNKNOWN }
    public enum Outcome { MET,NOT_MET,UNRESOLVED,NOT_APPLICABLE }
    public enum Reason { PRIORITY_UNKNOWN,PRIORITY_OUTSIDE_SLA,EXCLUSION_UNKNOWN,APPROVAL_EVIDENCE_MISSING,APPROVED_EXCLUSION,SLA_SNAPSHOT_MISSING,RESPONSE_DUE_MISSING,RESPONSE_ACTUAL_MISSING,RECOVERY_DUE_MISSING,RECOVERY_ACTUAL_MISSING,RESPONSE_LATE,RECOVERY_LATE }
    public enum AggregateStatus { CALCULATED,UNRESOLVED,NO_APPLICABLE }
    public enum AggregateReason { POPULATION_NOT_COMPLETE,APPLICABILITY_UNKNOWN,TIMING_UNRESOLVED,NO_APPLICABLE_TICKETS }
    /** Reference is an explicit scenario input, not proof that customer/manager approval or publication exists. */
    public record Reference(String id,long version){public Reference{if(!validReferenceText(id)||version<1||version>9007199254740991L)throw invalid();}}
    public record Evidence(String ticketId,Priority priority,Exclusion exclusion,Reference exclusionApproval,Reference slaSnapshot,Instant responseDueAt,Instant recoveryDueAt,Instant responseActualAt,Instant recoveryActualAt){
        public Evidence{if(!validReferenceText(ticketId))throw invalid();for(Instant time:new Instant[]{responseDueAt,recoveryDueAt,responseActualAt,recoveryActualAt})if(time!=null)validateTime(time);}
    }
    public record Evaluation(Evidence evidence,Boolean applicable,Outcome outcome,Check response,Check recovery,boolean exclusionApplied,boolean unapprovedExclusionIgnored,List<Reason> reasons){public Evaluation{Objects.requireNonNull(evidence);Objects.requireNonNull(outcome);Objects.requireNonNull(response);Objects.requireNonNull(recovery);reasons=List.copyOf(reasons);}}
    /** Exact numerator/denominator; no unstated decimal or compensation rounding policy. */
    public record Fraction(long numerator,long denominator){public Fraction{if(denominator<1||numerator<0||numerator>denominator)throw invalid();}}
    public record Aggregate(AggregateStatus status,AggregateReason reason,List<Evaluation> items,int knownApplicable,int applicabilityUnknown,int met,int notMet,int timingUnresolved,int notApplicable,Fraction scenarioAchievement){
        public Aggregate{Objects.requireNonNull(status);items=List.copyOf(items);if(knownApplicable<0||applicabilityUnknown<0||met<0||notMet<0||timingUnresolved<0||notApplicable<0||knownApplicable!=met+notMet+timingUnresolved||items.size()!=knownApplicable+applicabilityUnknown+notApplicable)throw invalid();if(status==AggregateStatus.CALCULATED){if(reason!=null||scenarioAchievement==null||scenarioAchievement.numerator()!=met||scenarioAchievement.denominator()!=knownApplicable||applicabilityUnknown!=0||timingUnresolved!=0)throw invalid();}else if(reason==null||scenarioAchievement!=null)throw invalid();}
        public String scope(){return "EXPLICIT_SLA_COMPARISON_SCENARIO";}
        public String metricVersion(){return "contract-m5-19-comparison-v1";}
        public boolean effective(){return false;}
        public Fraction effectiveAchievement(){return null;}
        public BigDecimal compensationAmount(){return null;}
        public String compensationStatus(){return "CONFIG_PENDING";}
    }
    public static Evaluation evaluate(Evidence value){
        if(value==null)throw invalid();List<Reason> reasons=new ArrayList<>();
        if(value.priority()==null)return result(value,null,Outcome.UNRESOLVED,false,false,List.of(Reason.PRIORITY_UNKNOWN));
        if(value.priority()==Priority.P4)return result(value,false,Outcome.NOT_APPLICABLE,false,false,List.of(Reason.PRIORITY_OUTSIDE_SLA));
        if(value.exclusion()==null||value.exclusion()==Exclusion.UNKNOWN)return result(value,null,Outcome.UNRESOLVED,false,false,List.of(Reason.EXCLUSION_UNKNOWN));
        if(value.exclusion()==Exclusion.APPROVED){if(value.exclusionApproval()==null)return result(value,null,Outcome.UNRESOLVED,false,false,List.of(Reason.APPROVAL_EVIDENCE_MISSING));return result(value,false,Outcome.NOT_APPLICABLE,true,false,List.of(Reason.APPROVED_EXCLUSION));}
        boolean ignored=value.exclusion()==Exclusion.PENDING_APPROVAL||value.exclusion()==Exclusion.REJECTED;
        if(value.slaSnapshot()==null)return result(value,true,Outcome.UNRESOLVED,false,ignored,List.of(Reason.SLA_SNAPSHOT_MISSING));
        Check response=check(value.responseDueAt(),value.responseActualAt(),Reason.RESPONSE_DUE_MISSING,Reason.RESPONSE_ACTUAL_MISSING,Reason.RESPONSE_LATE,reasons);
        Check recovery=check(value.recoveryDueAt(),value.recoveryActualAt(),Reason.RECOVERY_DUE_MISSING,Reason.RECOVERY_ACTUAL_MISSING,Reason.RECOVERY_LATE,reasons);
        Outcome outcome=response==Check.NOT_MET||recovery==Check.NOT_MET?Outcome.NOT_MET:response==Check.MET&&recovery==Check.MET?Outcome.MET:Outcome.UNRESOLVED;
        return new Evaluation(value,true,outcome,response,recovery,false,ignored,reasons);
    }
    private static Check check(Instant due,Instant actual,Reason missingDue,Reason missingActual,Reason late,List<Reason> reasons){
        if(due==null)reasons.add(missingDue);if(actual==null)reasons.add(missingActual);if(due==null||actual==null)return Check.UNKNOWN;
        if(actual.isAfter(due)){reasons.add(late);return Check.NOT_MET;}return Check.MET;
    }
    private static Evaluation result(Evidence value,Boolean applicable,Outcome outcome,boolean excluded,boolean ignored,List<Reason> reasons){return new Evaluation(value,applicable,outcome,Check.UNKNOWN,Check.UNKNOWN,excluded,ignored,reasons);}
    public static Aggregate aggregate(List<Evidence> values,Boolean populationComplete){
        if(values==null||values.size()>MAX_TICKETS)throw invalid();Set<String> seen=new HashSet<>();List<Evaluation> items=new ArrayList<>();int applicable=0,unknownScope=0,met=0,notMet=0,timingUnknown=0,outside=0;
        for(Evidence value:values){if(value==null||!seen.add(value.ticketId()))throw invalid();Evaluation result=evaluate(value);items.add(result);if(result.applicable()==null){unknownScope++;continue;}if(!result.applicable()){outside++;continue;}applicable++;switch(result.outcome()){case MET->met++;case NOT_MET->notMet++;case UNRESOLVED->timingUnknown++;default->throw new IllegalStateException("Inconsistent applicable scenario");}}
        AggregateReason reason=!Boolean.TRUE.equals(populationComplete)?AggregateReason.POPULATION_NOT_COMPLETE:unknownScope>0?AggregateReason.APPLICABILITY_UNKNOWN:timingUnknown>0?AggregateReason.TIMING_UNRESOLVED:applicable==0?AggregateReason.NO_APPLICABLE_TICKETS:null;
        AggregateStatus status=reason==null?AggregateStatus.CALCULATED:reason==AggregateReason.NO_APPLICABLE_TICKETS?AggregateStatus.NO_APPLICABLE:AggregateStatus.UNRESOLVED;
        return new Aggregate(status,reason,items,applicable,unknownScope,met,notMet,timingUnknown,outside,reason==null?new Fraction(met,applicable):null);
    }
    public static boolean validReferenceText(String value){return value!=null&&!value.isEmpty()&&value.codePointCount(0,value.length())<=160&&!value.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)&&!value.codePoints().anyMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.SURROGATE);}
    private static void validateTime(Instant time){if(time.getNano()%1000000!=0||time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||time.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","SLA对照输入、明确快照引用、时间精度或有界工单集合无效；不补截止时间或金额");}
}
