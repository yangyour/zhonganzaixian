package cn.mop.domain.dashboard;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.MonitoringEventPipeline.Severity;
import cn.mop.domain.time.IntervalSets;
import cn.mop.domain.time.IntervalSets.Interval;
import java.time.Instant;
import java.util.*;

/** Evidence algebra for explicitly mapped services. Absence of faults is not health evidence. */
public final class ServiceObservationIntervals {
    private ServiceObservationIntervals() {}
    public enum FailureMode { ANY_ASSET_UNAVAILABLE, ALL_ASSETS_UNAVAILABLE }
    public enum MaintenanceMode { ANY_ASSET_APPROVED, ALL_ASSETS_APPROVED }
    public enum Kind { FAULT, MAINTENANCE, UNKNOWN, AVAILABLE }
    public record Fault(Interval interval, Severity severity) {
        public Fault { Objects.requireNonNull(interval); Objects.requireNonNull(severity); }
    }
    public record Evidence(List<Fault> faults, List<Interval> unknown,
                           List<Interval> approvedMaintenance, List<Interval> healthy) {
        public Evidence {
            faults=List.copyOf(faults); unknown=List.copyOf(unknown);
            approvedMaintenance=List.copyOf(approvedMaintenance); healthy=List.copyOf(healthy);
        }
    }
    public record Result(List<Interval> unavailable, List<Interval> healthy,
                         List<Interval> unknown, List<Interval> maintenance) {
        public Result {
            unavailable=List.copyOf(unavailable); healthy=List.copyOf(healthy);
            unknown=List.copyOf(unknown); maintenance=List.copyOf(maintenance);
        }
    }
    public record Span(Kind kind, Severity severity, Instant start, Instant end) {
        public Span {
            Objects.requireNonNull(kind); new Interval(start,end);
            if ((kind==Kind.FAULT)!=(severity!=null)) throw invalid();
        }
    }
    public static Result evaluate(List<Evidence> assets, FailureMode failureMode,
                                  MaintenanceMode maintenanceMode, Interval window) {
        Objects.requireNonNull(failureMode); Objects.requireNonNull(maintenanceMode); Objects.requireNonNull(window);
        if(assets==null || assets.isEmpty() || assets.size()>100) throw invalid();
        List<Interval> down=null, up=null, maintenance=null;
        for(var asset:assets) {
            var raw=asset.faults().stream().map(Fault::interval).toList();
            var uncertain=new ArrayList<>(asset.unknown());
            uncertain.addAll(IntervalSets.intersection(raw,asset.healthy()));
            var assetDown=IntervalSets.clip(IntervalSets.subtract(raw,uncertain),window);
            var assetUp=IntervalSets.clip(IntervalSets.subtract(asset.healthy(),uncertain),window);
            down=combine(down,assetDown,failureMode==FailureMode.ANY_ASSET_UNAVAILABLE);
            up=combine(up,assetUp,failureMode==FailureMode.ALL_ASSETS_UNAVAILABLE);
            maintenance=combine(maintenance,IntervalSets.clip(asset.approvedMaintenance(),window),
                maintenanceMode==MaintenanceMode.ANY_ASSET_APPROVED);
        }
        var known=new ArrayList<>(down); known.addAll(up);
        return new Result(down,up,IntervalSets.subtract(List.of(window),known),maintenance);
    }
    private static List<Interval> combine(List<Interval> accumulated,List<Interval> next,boolean union) {
        if(accumulated==null) return next;
        if(!union) return IntervalSets.intersection(accumulated,next);
        var all=new ArrayList<>(accumulated); all.addAll(next); return IntervalSets.union(all);
    }
    /** Exact, gap-free partition, with approved maintenance taking precedence. No percentage rounding. */
    public static List<Span> timeline(Result result,List<Evidence> assets,Interval window) {
        var spans=new ArrayList<Span>();
        var maintenance=IntervalSets.clip(result.maintenance(),window);
        var included=IntervalSets.subtract(List.of(window),maintenance);
        add(spans,Kind.MAINTENANCE,maintenance);
        add(spans,Kind.UNKNOWN,IntervalSets.intersection(result.unknown(),included));
        add(spans,Kind.AVAILABLE,IntervalSets.intersection(result.healthy(),included));
        for(var fault:IntervalSets.intersection(result.unavailable(),included)) {
            var edges=new TreeMap<Instant,int[]>();
            for(var asset:assets) for(var source:asset.faults()) {
                for(var clipped:IntervalSets.clip(List.of(source.interval()),fault)) {
                    edges.computeIfAbsent(clipped.start(),ignored->new int[Severity.values().length])[source.severity().ordinal()]++;
                    edges.computeIfAbsent(clipped.end(),ignored->new int[Severity.values().length])[source.severity().ordinal()]--;
                }
            }
            int[] active=new int[Severity.values().length]; Instant previous=null;
            for(var edge:edges.entrySet()) {
                if(previous!=null && previous.isBefore(edge.getKey())) {
                    Severity highest=null;
                    for(int i=active.length-1;i>=0;i--) if(active[i]>0) { highest=Severity.values()[i]; break; }
                    if(highest==null) throw invalid();
                    spans.add(new Span(Kind.FAULT,highest,previous,edge.getKey()));
                    limit(spans);
                }
                for(int i=0;i<active.length;i++) { active[i]+=edge.getValue()[i]; if(active[i]<0) throw invalid(); }
                previous=edge.getKey();
            }
        }
        spans.sort(Comparator.comparing(Span::start).thenComparing(Span::end));
        Instant cursor=window.start();
        for(var span:spans) { if(!cursor.equals(span.start())) throw invalid(); cursor=span.end(); }
        if(!cursor.equals(window.end())) throw invalid();
        limit(spans); return List.copyOf(spans);
    }
    private static void add(List<Span> spans,Kind kind,List<Interval> intervals) {
        for(var interval:intervals) { spans.add(new Span(kind,null,interval.start(),interval.end())); limit(spans); }
    }
    private static void limit(List<Span> spans) {
        if(spans.size()>2000) throw new DomainException(422,"SERVICE_OBSERVATION_LIMIT","服务时间线超过读取上限");
    }
    private static DomainException invalid() {
        return new DomainException(503,"SERVICE_OBSERVATION_INVALID","服务观测证据不完整或不一致");
    }
}
