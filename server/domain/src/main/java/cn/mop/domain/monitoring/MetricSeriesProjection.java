package cn.mop.domain.monitoring;

import cn.mop.domain.DomainException;
import cn.mop.domain.time.BusinessDates;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Bounded adapter-input projection, not authorization, source verification, alerting or persisted monitoring. */
public final class MetricSeriesProjection {
    public static final int MAX_POINTS=10_000;
    public enum Quality { SAMPLE_PRESENT, UNKNOWN }
    public enum MissingReason { NO_SAMPLE, COLLECTOR_UNAVAILABLE, UNSUPPORTED_ITEM, INVALID_PROVIDER_VALUE }
    public record Scope(UUID tenantId,UUID assetId,String metricKey) {
        public Scope {if(tenantId==null||assetId==null||metricKey==null||!metricKey.matches("[a-z][a-z0-9_]*(?:\\.[a-z0-9_]+){0,15}")||metricKey.length()>128)throw invalid();}
    }
    /** Explicit unit mapping input. Its version is retained but approval/authenticity is not established here. */
    public record Conversion(String mappingVersion,String originalUnit,String targetUnit,String multiplier,String offset) {
        public Conversion {label(mappingVersion,128);unit(originalUnit);unit(targetUnit);number(multiplier);number(offset);if(new BigDecimal(multiplier).signum()<=0)throw invalid();}
    }
    public record Point(Scope scope,String sourcePointId,Instant sourceRecordedAt,String originalValue,String originalUnit,MissingReason missingReason) {
        public Point {
            if(scope==null)throw invalid();label(sourcePointId,128);instant(sourceRecordedAt);unit(originalUnit);
            if(originalValue==null?missingReason==null:missingReason!=null)throw invalid();
            if(originalValue!=null)number(originalValue);
        }
    }
    public record ProjectedPoint(String sourcePointId,Instant sourceRecordedAt,String originalValue,String originalUnit,
                                 String value,String targetUnit,Quality quality,String missingReason,String mappingVersion) {
        public ProjectedPoint {
            label(sourcePointId,128);instant(sourceRecordedAt);unit(originalUnit);
            if(originalValue!=null)number(originalValue);
            if((targetUnit==null)!=(mappingVersion==null)||quality==null)throw invalid();
            if(targetUnit!=null){unit(targetUnit);label(mappingVersion,128);}
            if(quality==Quality.SAMPLE_PRESENT){
                if(originalValue==null||value==null||targetUnit==null||missingReason!=null
                        ||value.length()>164||!value.matches("-?(?:0|[1-9][0-9]{0,80})(?:\\.[0-9]{1,80})?")
                        ||!decimal(new BigDecimal(value)).equals(value))throw invalid();
            }else{
                if(value!=null||missingReason==null)throw invalid();
                if(originalValue!=null){if(targetUnit!=null||!missingReason.equals("UNIT_MAPPING_UNAVAILABLE"))throw invalid();}
                else{try{MissingReason.valueOf(missingReason);}catch(IllegalArgumentException error){throw invalid();}}
            }
        }
        /** A source record with no value is not a newly sampled metric. Keep its gap time separately. */
        public Instant sampledAt(){return originalValue==null?null:sourceRecordedAt;}
    }
    private final Scope scope;
    private final Instant from,to,observedAt;
    private final List<ProjectedPoint> points;
    private final String seriesStatus;
    private MetricSeriesProjection(Scope scope,Instant from,Instant to,Instant observedAt,List<ProjectedPoint> points,String status){this.scope=scope;this.from=from;this.to=to;this.observedAt=observedAt;this.points=points;this.seriesStatus=status;}

    /** Null collection means no reader result. An explicit empty collection remains distinct and never becomes zero. */
    public static MetricSeriesProjection project(Scope scope,Instant from,Instant to,Instant observedAt,List<Point> input,Conversion conversion){
        if(scope==null)throw invalid();instant(from);instant(to);instant(observedAt);if(!from.isBefore(to)||to.isAfter(observedAt))throw invalid();
        if(input==null)return new MetricSeriesProjection(scope,from,to,observedAt,null,"READER_RESULT_UNAVAILABLE");
        if(input.size()>MAX_POINTS)throw large();
        var result=new ArrayList<ProjectedPoint>(input.size());var identities=new HashSet<String>();Instant previous=null;
        for(Point point:input){
            if(point==null||!scope.equals(point.scope())||point.sourceRecordedAt().isBefore(from)||point.sourceRecordedAt().isAfter(to)
                    ||previous!=null&&point.sourceRecordedAt().isBefore(previous)||!identities.add(point.sourcePointId()))throw invalid();
            previous=point.sourceRecordedAt();
            if(conversion!=null&&!conversion.originalUnit().equals(point.originalUnit()))throw invalid();
            String value=null,reason=null;Quality quality=Quality.UNKNOWN;
            if(point.originalValue()==null)reason=point.missingReason().name();
            else if(conversion==null)reason="UNIT_MAPPING_UNAVAILABLE";
            else {
                BigDecimal converted=new BigDecimal(point.originalValue()).multiply(new BigDecimal(conversion.multiplier())).add(new BigDecimal(conversion.offset()));
                value=decimal(converted);quality=Quality.SAMPLE_PRESENT;
            }
            result.add(new ProjectedPoint(point.sourcePointId(),point.sourceRecordedAt(),point.originalValue(),point.originalUnit(),value,
                    conversion==null?null:conversion.targetUnit(),quality,reason,conversion==null?null:conversion.mappingVersion()));
        }
        return new MetricSeriesProjection(scope,from,to,observedAt,List.copyOf(result),result.isEmpty()?"EXPLICIT_EMPTY_RESULT":"PROJECTED_ADAPTER_INPUT");
    }
    private static void number(String value){
        if(value==null||value.length()>83||!value.matches("-?(?:0|[1-9][0-9]{0,39})(?:\\.[0-9]{1,40})?")||value.startsWith("-0")&&new BigDecimal(value).signum()==0)throw invalid();
    }
    private static String decimal(BigDecimal value){return value.signum()==0?"0":value.stripTrailingZeros().toPlainString();}
    private static void unit(String value){
        if(value==null||value.isEmpty()||value.codePointCount(0,value.length())>32||value.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||c>=0xD800&&c<=0xDFFF))throw invalid();
        int first=value.codePointAt(0),last=value.codePointBefore(value.length());
        if(Character.isWhitespace(first)||Character.isSpaceChar(first)||Character.isWhitespace(last)||Character.isSpaceChar(last))throw invalid();
    }
    private static void label(String value,int max){if(value==null||value.length()>max||!value.matches("[A-Za-z0-9][A-Za-z0-9._:/% -]*")||!value.equals(value.strip()))throw invalid();}
    private static void instant(Instant value){if(value==null||value.getNano()%1_000_000!=0||value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();BusinessDates.localDate(value);}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","指标点需明确范围、原值/原单位/时间与来源标识；未知不补零，缺单位映射不猜转换，重复来源或乱序不静默丢弃");}
    private static DomainException large(){return new DomainException(413,"REQUEST_TOO_LARGE","指标点超过10000项技术安全界限；未截断、未擅自聚合");}
    public Scope scope(){return scope;}public Instant from(){return from;}public Instant to(){return to;}public Instant observedAt(){return observedAt;}public List<ProjectedPoint> points(){return points;}public String seriesStatus(){return seriesStatus;}
    public String source(){return "EXPLICIT_ADAPTER_INPUT_NOT_VERIFIED_MONITORING";}
    public String numericEncoding(){return "BASE10_STRINGS";}
    public String ruleVersion(){return null;}public String effectiveRule(){return null;}public String availability(){return "UNKNOWN";}
    public Boolean monitoringReady(){return null;}public boolean sourceVerified(){return false;}public boolean mappingApprovalVerified(){return false;}
    public boolean authorizationVerified(){return false;}public boolean interpolated(){return false;}public boolean aggregationApplied(){return false;}public boolean writesPerformed(){return false;}
}
