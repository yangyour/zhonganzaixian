package cn.mop.domain.operations;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Contract arithmetic over explicit inputs only. Does not select Q23/Q27/Q30 business definitions or query project data. */
public final class ExplicitOperationalMetrics {
    public static final int MAX_SAMPLES=10000;
    private ExplicitOperationalMetrics() {}
    public enum Status { CALCULATED, UNRESOLVED, UNDEFINED }
    public enum Reason { ALL_EXPLICIT, INPUT_UNAVAILABLE, UNKNOWN_SAMPLE, EMPTY_SAMPLE, MISSING_INPUT, ZERO_FTE }
    public record MedianResult(Status status,Reason reason,Integer sampleCount,Integer unknownCount,BigDecimal sampleMedianMillis) {
        public MedianResult {
            if(status==null||reason==null)throw invalid();
            boolean countKnown=sampleCount!=null&&sampleCount>=0&&sampleCount<=MAX_SAMPLES&&unknownCount!=null&&unknownCount>=0&&unknownCount<=sampleCount;
            boolean valid=switch(reason){
                case INPUT_UNAVAILABLE -> status==Status.UNRESOLVED&&sampleCount==null&&unknownCount==null&&sampleMedianMillis==null;
                case EMPTY_SAMPLE -> status==Status.UNDEFINED&&countKnown&&sampleCount==0&&sampleMedianMillis==null;
                case UNKNOWN_SAMPLE -> status==Status.UNRESOLVED&&countKnown&&unknownCount>0&&sampleMedianMillis==null;
                case ALL_EXPLICIT -> status==Status.CALCULATED&&countKnown&&sampleCount>0&&unknownCount==0&&sampleMedianMillis!=null&&sampleMedianMillis.signum()>=0&&sampleMedianMillis.scale()<=1&&sampleMedianMillis.precision()-sampleMedianMillis.scale()<=40;
                default -> false;
            };
            if(!valid)throw invalid();
        }
        public String formulaVersion(){return "explicit-elapsed-median-v1";}
        public String unit(){return "milliseconds";}
        public String source(){return "EXPLICIT_INPUT_NOT_PROJECT_METRICS";}
        public String durationBasisVerification(){return "UNVERIFIED_Q27";}
        public String populationVerification(){return "UNVERIFIED";}
        public BigDecimal effectiveProcessingDuration(){return null;}
    }
    /** Zero is a supplied duration. Null is unavailable and must never be filtered out to create a result. */
    public static MedianResult medianElapsedMillis(List<BigInteger> durations){
        if(durations==null)return new MedianResult(Status.UNRESOLVED,Reason.INPUT_UNAVAILABLE,null,null,null);
        if(durations.size()>MAX_SAMPLES)throw invalid();
        var sorted=new ArrayList<BigInteger>();int unknown=0;
        for(var duration:durations){count(duration);if(duration==null)unknown++;else sorted.add(duration);}
        if(unknown>0)return new MedianResult(Status.UNRESOLVED,Reason.UNKNOWN_SAMPLE,durations.size(),unknown,null);
        if(sorted.isEmpty())return new MedianResult(Status.UNDEFINED,Reason.EMPTY_SAMPLE,0,0,null);
        sorted.sort(BigInteger::compareTo);int size=sorted.size();
        BigDecimal median=size%2==1?new BigDecimal(sorted.get(size/2)):new BigDecimal(sorted.get(size/2-1).add(sorted.get(size/2))).divide(BigDecimal.valueOf(2));
        return new MedianResult(Status.CALCULATED,Reason.ALL_EXPLICIT,size,0,median);
    }
    public record StaffingInput(BigInteger activeTenants,BigDecimal onCallFte,BigDecimal secondLineFte) {}
    public record StaffingResult(Status status,Reason reason,BigDecimal explicitTotalFte,BigDecimal sampleTenantsPerFte,List<String> missingInputs) {
        public StaffingResult {
            if(status==null||reason==null||missingInputs==null||missingInputs.stream().anyMatch(v->v==null)||new HashSet<>(missingInputs).size()!=missingInputs.size())throw invalid();
            missingInputs=List.copyOf(missingInputs);
            if(explicitTotalFte!=null&&(explicitTotalFte.signum()<0||explicitTotalFte.scale()>40||explicitTotalFte.precision()-explicitTotalFte.scale()>41)||sampleTenantsPerFte!=null&&(sampleTenantsPerFte.signum()<0||sampleTenantsPerFte.precision()>34))throw invalid();
            boolean valid=switch(reason){
                case INPUT_UNAVAILABLE -> status==Status.UNRESOLVED&&explicitTotalFte==null&&sampleTenantsPerFte==null&&missingInputs.equals(List.of("input"));
                case MISSING_INPUT -> status==Status.UNRESOLVED&&sampleTenantsPerFte==null&&!missingInputs.isEmpty()&&Set.of("active_tenants","on_call_fte","second_line_fte").containsAll(missingInputs)&&((missingInputs.contains("on_call_fte")||missingInputs.contains("second_line_fte"))?explicitTotalFte==null:explicitTotalFte!=null);
                case ZERO_FTE -> status==Status.UNDEFINED&&explicitTotalFte!=null&&explicitTotalFte.signum()==0&&sampleTenantsPerFte==null&&missingInputs.isEmpty();
                case ALL_EXPLICIT -> status==Status.CALCULATED&&explicitTotalFte!=null&&explicitTotalFte.signum()>0&&sampleTenantsPerFte!=null&&missingInputs.isEmpty();
                default -> false;
            };
            if(!valid)throw invalid();
        }
        public String formulaVersion(){return "explicit-tenants-per-fte-v1";}
        public String source(){return "EXPLICIT_INPUT_NOT_PROJECT_METRICS";}
        public String decimalPrecision(){return "DECIMAL128";}
        public String populationVerification(){return "UNVERIFIED_Q30";}
        public String staffingBasisVerification(){return "UNVERIFIED_Q23";}
        public BigDecimal effectiveTenantsPerFte(){return null;}
        public Boolean targetMet(){return null;}
        public boolean billingApplicable(){return false;}
    }
    /** FTE is the exact sum of two explicitly supplied monthly equivalents, not head count or a default staffing rule. */
    public static StaffingResult tenantsPerFte(StaffingInput input){
        if(input==null)return new StaffingResult(Status.UNRESOLVED,Reason.INPUT_UNAVAILABLE,null,null,List.of("input"));
        count(input.activeTenants());decimal(input.onCallFte());decimal(input.secondLineFte());var missing=new ArrayList<String>();
        if(input.activeTenants()==null)missing.add("active_tenants");if(input.onCallFte()==null)missing.add("on_call_fte");if(input.secondLineFte()==null)missing.add("second_line_fte");
        BigDecimal fte=input.onCallFte()==null||input.secondLineFte()==null?null:input.onCallFte().add(input.secondLineFte());
        if(!missing.isEmpty())return new StaffingResult(Status.UNRESOLVED,Reason.MISSING_INPUT,fte,null,missing);
        if(fte.signum()==0)return new StaffingResult(Status.UNDEFINED,Reason.ZERO_FTE,fte,null,List.of());
        return new StaffingResult(Status.CALCULATED,Reason.ALL_EXPLICIT,fte,new BigDecimal(input.activeTenants()).divide(fte,MathContext.DECIMAL128),List.of());
    }
    private static void count(BigInteger value){if(value!=null&&(value.signum()<0||value.toString().length()>40))throw invalid();}
    private static void decimal(BigDecimal value){if(value!=null&&(value.signum()<0||value.scale()>40||value.scale() < -40||value.precision()-value.scale()>40))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Explicit nonnegative bounded operational inputs required; no business defaults selected");}
}
