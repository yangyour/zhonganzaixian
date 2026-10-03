package cn.mop.domain.monitoring;

import java.math.BigDecimal;
import java.util.*;

/** Exact predicates over explicit candidate values, never a published threshold, measured event or customer approval. */
public final class ThresholdConditionAnalysis {
    private ThresholdConditionAnalysis() {}
    public enum Direction { HIGH_IS_BAD, LOW_IS_BAD }
    public enum Comparison { GT, GE, LT, LE }
    public enum Status { CONSISTENT, UNRESOLVED, CONFLICT }
    public enum Code {
        DIRECTION_MISSING, WARNING_CONDITION_INCOMPLETE, CRITICAL_CONDITION_INCOMPLETE, RECOVERY_CONDITION_INCOMPLETE,
        UNIT_MISMATCH, WARNING_DIRECTION_CONFLICT, CRITICAL_DIRECTION_CONFLICT, RECOVERY_DIRECTION_CONFLICT,
        CRITICAL_NOT_STRICTER_THAN_WARNING, RECOVERY_OVERLAPS_WARNING, RECOVERY_OVERLAPS_CRITICAL,
        TRIGGER_DURATION_MISSING, RECOVERY_DURATION_MISSING, NEGATIVE_TRIGGER_DURATION, NEGATIVE_RECOVERY_DURATION
    }
    public record Condition(Comparison comparison,BigDecimal threshold,String unit) {
        public Condition { bounded(threshold);if(unit!=null)validateUnit(unit); }
    }
    public record Input(Direction direction,Condition warning,Condition critical,Condition recovery,
                        BigDecimal triggerHoldSeconds,BigDecimal recoveryHoldSeconds) {
        public Input { bounded(triggerHoldSeconds);bounded(recoveryHoldSeconds); }
    }
    public record Issue(Code code,Status kind) {
        public Issue { if(code==null||kind==null||kind==Status.CONSISTENT)throw invalid(); }
    }
    public record Result(Status status,Boolean directionConsistent,Boolean criticalStrictSubsetOfWarning,
                         Boolean recoveryOverlapsWarning,Boolean recoveryOverlapsCritical,
                         Boolean durationsNonnegative,List<Issue> issues) {
        public Result {Objects.requireNonNull(status);issues=List.copyOf(issues);}
        public boolean publishable(){return false;}
        public Object effectiveRule(){return null;}
        public String customerConfirmation(){return "UNVERIFIED";}
        public String physicalRangeCheck(){return "NOT_RUN";}
        public String scope(){return "EXPLICIT_PREDICATE_DIAGNOSTIC_ONLY";}
        public boolean unitsConverted(){return false;}
        public boolean monitoringApplied(){return false;}
    }
    public static Result analyze(Input input){
        if(input==null)input=new Input(null,null,null,null,null,null);
        List<Issue> issues=new ArrayList<>();
        Direction direction=input.direction();Condition warning=input.warning(),critical=input.critical(),recovery=input.recovery();
        if(direction==null)pending(issues,Code.DIRECTION_MISSING);
        if(!complete(warning))pending(issues,Code.WARNING_CONDITION_INCOMPLETE);
        if(!complete(critical))pending(issues,Code.CRITICAL_CONDITION_INCOMPLETE);
        if(!complete(recovery))pending(issues,Code.RECOVERY_CONDITION_INCOMPLETE);
        Set<String> units=new HashSet<>();for(Condition c:Arrays.asList(warning,critical,recovery))if(c!=null&&c.unit()!=null)units.add(c.unit());
        if(units.size()>1)conflict(issues,Code.UNIT_MISMATCH);
        Boolean wd=direction(warning,direction,false),cd=direction(critical,direction,false),rd=direction(recovery,direction,true);
        if(Boolean.FALSE.equals(wd))conflict(issues,Code.WARNING_DIRECTION_CONFLICT);
        if(Boolean.FALSE.equals(cd))conflict(issues,Code.CRITICAL_DIRECTION_CONFLICT);
        if(Boolean.FALSE.equals(rd))conflict(issues,Code.RECOVERY_DIRECTION_CONFLICT);
        Boolean consistent=wd==null||cd==null||rd==null?null:wd&&cd&&rd;
        Boolean strict=comparable(critical,warning)?subset(critical,warning)&&!equalPredicates(critical,warning):null;
        if(Boolean.FALSE.equals(strict))conflict(issues,Code.CRITICAL_NOT_STRICTER_THAN_WARNING);
        Boolean overlapWarning=comparable(recovery,warning)?overlap(recovery,warning):null;
        Boolean overlapCritical=comparable(recovery,critical)?overlap(recovery,critical):null;
        if(Boolean.TRUE.equals(overlapWarning))conflict(issues,Code.RECOVERY_OVERLAPS_WARNING);
        if(Boolean.TRUE.equals(overlapCritical))conflict(issues,Code.RECOVERY_OVERLAPS_CRITICAL);
        BigDecimal trigger=input.triggerHoldSeconds(),recover=input.recoveryHoldSeconds();
        if(trigger==null)pending(issues,Code.TRIGGER_DURATION_MISSING);else if(trigger.signum()<0)conflict(issues,Code.NEGATIVE_TRIGGER_DURATION);
        if(recover==null)pending(issues,Code.RECOVERY_DURATION_MISSING);else if(recover.signum()<0)conflict(issues,Code.NEGATIVE_RECOVERY_DURATION);
        Boolean durations=trigger==null||recover==null?null:trigger.signum()>=0&&recover.signum()>=0;
        Status status=issues.stream().anyMatch(i->i.kind()==Status.CONFLICT)?Status.CONFLICT:issues.isEmpty()?Status.CONSISTENT:Status.UNRESOLVED;
        return new Result(status,consistent,strict,overlapWarning,overlapCritical,durations,issues);
    }
    private static void pending(List<Issue> issues,Code code){issues.add(new Issue(code,Status.UNRESOLVED));}
    private static void conflict(List<Issue> issues,Code code){issues.add(new Issue(code,Status.CONFLICT));}
    private static boolean complete(Condition c){return c!=null&&c.comparison()!=null&&c.threshold()!=null&&c.unit()!=null;}
    private static boolean comparable(Condition a,Condition b){return complete(a)&&complete(b)&&a.unit().equals(b.unit());}
    private static boolean upper(Comparison c){return c==Comparison.GT||c==Comparison.GE;}
    private static boolean inclusive(Comparison c){return c==Comparison.GE||c==Comparison.LE;}
    private static Boolean direction(Condition c,Direction direction,boolean recovery){if(c==null||c.comparison()==null||direction==null)return null;boolean expectedUpper=direction==Direction.HIGH_IS_BAD;return upper(c.comparison())==(recovery?!expectedUpper:expectedUpper);}
    /** Half-lines over real numbers, without silently imposing metric-specific physical bounds. */
    private static boolean subset(Condition inner,Condition outer){if(upper(inner.comparison())!=upper(outer.comparison()))return false;int order=inner.threshold().compareTo(outer.threshold());if(order==0)return !inclusive(inner.comparison())||inclusive(outer.comparison());return upper(inner.comparison())?order>0:order<0;}
    private static boolean equalPredicates(Condition a,Condition b){return a.comparison()==b.comparison()&&a.threshold().compareTo(b.threshold())==0;}
    private static boolean overlap(Condition a,Condition b){if(upper(a.comparison())==upper(b.comparison()))return true;Condition high=upper(a.comparison())?a:b,low=upper(a.comparison())?b:a;int order=high.threshold().compareTo(low.threshold());return order<0||order==0&&inclusive(high.comparison())&&inclusive(low.comparison());}
    /** Exact root/condition fields. Decimal strings avoid binary rounding; null remains missing rather than zero. */
    public static Input fromWire(Object value){Map<?,?> v=fields(value,Set.of("direction","warning","critical","recovery","trigger_hold_seconds","recovery_hold_seconds"));Direction direction=null;if(v.get("direction")!=null){if(!(v.get("direction") instanceof String label))throw invalid();try{direction=Direction.valueOf(label);}catch(RuntimeException failure){throw invalid();}}return new Input(direction,condition(v.get("warning")),condition(v.get("critical")),condition(v.get("recovery")),decimal(v.get("trigger_hold_seconds")),decimal(v.get("recovery_hold_seconds")));}
    private static Condition condition(Object value){if(value==null)return null;Map<?,?> v=fields(value,Set.of("comparison","threshold","unit"));Comparison op=null;if(v.get("comparison")!=null){if(!(v.get("comparison") instanceof String label))throw invalid();try{op=Comparison.valueOf(label);}catch(RuntimeException failure){throw invalid();}}Object unit=v.get("unit");if(unit!=null&&!(unit instanceof String))throw invalid();return new Condition(op,decimal(v.get("threshold")),(String)unit);}
    private static Map<?,?> fields(Object value,Set<String> keys){if(!(value instanceof Map<?,?> map)||!map.keySet().equals(keys))throw invalid();return map;}
    private static BigDecimal decimal(Object value){if(value==null)return null;if(!(value instanceof String text)||!text.matches("-?(?:0|[1-9][0-9]{0,39})(?:\\.[0-9]{1,40})?"))throw invalid();BigDecimal parsed=new BigDecimal(text);if(text.startsWith("-")&&parsed.signum()==0)throw invalid();return parsed;}
    private static void bounded(BigDecimal value){if(value!=null&&(value.scale()>40||value.scale()< -40||value.precision()-value.scale()>40))throw invalid();}
    private static void validateUnit(String value){if(value.isEmpty()||value.codePointCount(0,value.length())>32||value.codePoints().allMatch(p->Character.isWhitespace(p)||Character.isSpaceChar(p)||p==0xfeff)||value.codePoints().anyMatch(p->Character.isISOControl(p)||Character.getType(p)==Character.SURROGATE))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid explicit threshold diagnostic input");}
}
