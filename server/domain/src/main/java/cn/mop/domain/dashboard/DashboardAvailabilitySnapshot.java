package cn.mop.domain.dashboard;

import cn.mop.domain.DomainException;
import cn.mop.domain.time.BusinessDates;
import java.time.*;
import java.util.*;

/** Contract-fixed layout and explicitly unavailable metric readers. Authorization and time are real; samples are not invented. */
public final class DashboardAvailabilitySnapshot {
    public enum TrendWindow { HOURS_24,DAYS_7,DAYS_30 }
    public enum KpiId { ASSET_ONLINE_RATE,CURRENT_PROBLEMS,MONTH_SERVICE_AVAILABILITY,PENDING_TICKETS,MTTA_24H,DELIVERY_RATE_24H }
    public enum PanelId { ALERT_TREND,SERVICE_HEALTH,FAULT_TIMELINE,RECENT_EVENTS,PENDING_TICKETS,RESOURCE_TOP5 }
    public record Period(Instant from,Instant to){public Period{instant(from);instant(to);if(from.isAfter(to))throw invalid();}}
    public record Kpi(KpiId id,String unit,Period period,String unavailableReason,Map<String,Long> detailCounts){
        public Kpi{Objects.requireNonNull(id);Objects.requireNonNull(unit);Objects.requireNonNull(unavailableReason);detailCounts=Collections.unmodifiableMap(new LinkedHashMap<>(detailCounts));if(detailCounts.values().stream().anyMatch(Objects::nonNull))throw invalid();}
        public Double value(){return null;}
        public Long sampleCount(){return null;}
        public Instant sampledAt(){return null;}
        public String metricVersion(){return null;}
        public Boolean thresholdBreached(){return null;}
        public String drilldownPath(){return null;}
        public String state(){return "UNKNOWN";}
        public String readerStatus(){return "NOT_IMPLEMENTED";}
    }
    public record Panel(PanelId id,Period period,Integer contractItemLimit,String unavailableReason){
        public Panel{Objects.requireNonNull(id);Objects.requireNonNull(unavailableReason);}
        public List<Object> items(){return null;}
        public Long sampleCount(){return null;}
        public Instant sampledAt(){return null;}
        public String metricVersion(){return null;}
        public String state(){return "UNKNOWN";}
        public String readerStatus(){return "NOT_IMPLEMENTED";}
    }
    private final UUID tenantId,actorId;
    private final long permissionVersion;
    private final Instant generatedAt;
    private final TrendWindow requestedWindow;
    private final Period trendPeriod,last24Hours;
    private final Instant monthStart;
    private final List<Kpi> kpis;
    private final List<Panel> panels;
    public DashboardAvailabilitySnapshot(UUID tenantId,UUID actorId,long permissionVersion,Instant generatedAt,TrendWindow window){
        if(tenantId==null||actorId==null||permissionVersion<1||permissionVersion>9_007_199_254_740_991L||window==null)throw invalid();instant(generatedAt);
        this.tenantId=tenantId;this.actorId=actorId;this.permissionVersion=permissionVersion;this.generatedAt=generatedAt;this.requestedWindow=window;
        try{
            long hours=switch(window){case HOURS_24->24;case DAYS_7->168;case DAYS_30->720;};
            trendPeriod=new Period(generatedAt.minus(Duration.ofHours(hours)),generatedAt);last24Hours=new Period(generatedAt.minus(Duration.ofHours(24)),generatedAt);
            monthStart=BusinessDates.localDate(generatedAt).withDayOfMonth(1).atStartOfDay(BusinessDates.ZONE).toInstant();instant(monthStart);
        }catch(DateTimeException|ArithmeticException e){throw invalid();}
        // At the exact first instant of a month, an empty month-to-date window is explicitly represented, not fabricated.
        Period month=new Period(monthStart,generatedAt);
        kpis=List.of(
            new Kpi(KpiId.ASSET_ONLINE_RATE,"PERCENT",null,"MONITORING_SAMPLE_READER_NOT_IMPLEMENTED",unknownCounts("normal","unknown","total")),
            new Kpi(KpiId.CURRENT_PROBLEMS,"COUNT",null,"EVENT_READER_NOT_IMPLEMENTED",unknownCounts("disaster","severe","average","warning","information")),
            new Kpi(KpiId.MONTH_SERVICE_AVAILABILITY,"PERCENT",month,"SERVICE_INTERVAL_READER_NOT_IMPLEMENTED",unknownCounts()),
            new Kpi(KpiId.PENDING_TICKETS,"COUNT",null,"FORMAL_TICKET_STATE_NOT_AVAILABLE",unknownCounts("overdue_sla")),
            new Kpi(KpiId.MTTA_24H,"MILLISECONDS",last24Hours,"EVENT_ACKNOWLEDGEMENT_READER_NOT_IMPLEMENTED",unknownCounts()),
            new Kpi(KpiId.DELIVERY_RATE_24H,"PERCENT",last24Hours,"DELIVERY_RECEIPT_READER_NOT_IMPLEMENTED",unknownCounts("accepted","delivered"))
        );
        panels=List.of(
            new Panel(PanelId.ALERT_TREND,trendPeriod,null,"EVENT_READER_NOT_IMPLEMENTED"),
            new Panel(PanelId.SERVICE_HEALTH,null,null,"SERVICE_INTERVAL_READER_NOT_IMPLEMENTED"),
            new Panel(PanelId.FAULT_TIMELINE,last24Hours,8,"SERVICE_INTERVAL_READER_NOT_IMPLEMENTED"),
            new Panel(PanelId.RECENT_EVENTS,null,7,"EVENT_READER_NOT_IMPLEMENTED"),
            new Panel(PanelId.PENDING_TICKETS,null,5,"FORMAL_TICKET_STATE_NOT_AVAILABLE"),
            new Panel(PanelId.RESOURCE_TOP5,null,5,"MONITORING_SAMPLE_READER_NOT_IMPLEMENTED")
        );
    }
    private static Map<String,Long> unknownCounts(String...names){var map=new LinkedHashMap<String,Long>();for(String name:names)map.put(name,null);return map;}
    public UUID tenantId(){return tenantId;}public UUID actorId(){return actorId;}public long permissionVersion(){return permissionVersion;}public Instant generatedAt(){return generatedAt;}public TrendWindow requestedWindow(){return requestedWindow;}public Period trendPeriod(){return trendPeriod;}public Period last24Hours(){return last24Hours;}public Instant monthStart(){return monthStart;}public List<Kpi> kpis(){return kpis;}public List<Panel> panels(){return panels;}
    public boolean monthWindowEmpty(){return monthStart.equals(generatedAt);}
    public String timezone(){return BusinessDates.ZONE.getId();}
    public String layoutVersion(){return "MOP_FIXED_DASHBOARD_V1";}
    public boolean layoutCustomizationAllowed(){return false;}
    public int contractRefreshSeconds(){return 30;}
    public boolean metricSamplesLoaded(){return false;}
    public boolean clockAccuracyVerified(){return false;}
    public String source(){return "POSTGRESQL_AUTHORIZATION_AND_CLOCK_ONLY";}
    public String scope(){return "CURRENT_AUTHORIZED_TENANT";}
    public String formalMetricStatus(){return "NOT_IMPLEMENTED";}
    private static void instant(Instant value){if(value==null||value.getNano()%1_000_000!=0||value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();BusinessDates.localDate(value);}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","首页可用性投影需明确授权范围、参考时刻及合同时间范围；不补指标样本或默认正常值");}
}
