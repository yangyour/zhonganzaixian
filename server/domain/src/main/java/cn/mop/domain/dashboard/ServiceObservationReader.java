package cn.mop.domain.dashboard;

import cn.mop.domain.DomainException;
import cn.mop.domain.sla.SlaAvailabilityAndCompensation;
import cn.mop.domain.sla.ServiceAvailabilityAggregate;
import cn.mop.domain.time.BusinessDates;
import cn.mop.domain.time.IntervalSets;
import cn.mop.domain.time.IntervalSets.Interval;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static cn.mop.domain.dashboard.ServiceObservationIntervals.*;

/** Tenant-scoped service mapping and evidence projection. Mapping is not formal customer acceptance. */
public interface ServiceObservationReader {
    String VERSION="MOP_CONFIGURED_SERVICE_OBSERVATIONS_V1";
    record Access(UUID tenantId,UUID actorId,long permissionVersion) {
        public Access {
            Objects.requireNonNull(tenantId); Objects.requireNonNull(actorId);
            if(permissionVersion<1||permissionVersion>9_007_199_254_740_991L) throw invalid();
        }
    }
    record Definition(UUID id,String name,List<UUID> assetIds,FailureMode failureMode,
                      MaintenanceMode maintenanceMode,BigDecimal targetPct) {
        public Definition {
            Objects.requireNonNull(id); Objects.requireNonNull(failureMode); Objects.requireNonNull(maintenanceMode);
            if(name==null||name.isBlank()||name.length()>200||name.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xD800&&c<=0xDFFF)) throw invalid();
            assetIds=List.copyOf(assetIds);
            if(assetIds.isEmpty()||assetIds.size()>100||new HashSet<>(assetIds).size()!=assetIds.size()) throw invalid();
            assetIds=assetIds.stream().sorted().toList();
            if(targetPct!=null&&(targetPct.signum()<0||targetPct.compareTo(BigDecimal.valueOf(100))>0||targetPct.scale()>4)) throw invalid();
        }
    }
    record Asset(UUID id,String name,long version) {
        public Asset {Objects.requireNonNull(id);if(name==null||name.isBlank()||version<1)throw invalid();}
    }
    record AssetEvidence(Asset asset,Evidence evidence,Set<UUID> activeProblems,Set<UUID> highProblems) {
        public AssetEvidence {
            Objects.requireNonNull(asset);Objects.requireNonNull(evidence);
            activeProblems=Set.copyOf(activeProblems);highProblems=Set.copyOf(highProblems);
            if(!activeProblems.containsAll(highProblems))throw invalid();
        }
    }
    record Service(UUID id,String name,List<Asset> assets,FailureMode failureMode,MaintenanceMode maintenanceMode,
                   BigDecimal targetPct,BigDecimal verifiedAvailabilityPct,long knownAvailableMillis,
                   long effectiveMillis,long unavailableMillis,long unknownMillis,long exclusionMillis,
                   int activeProblems,int highProblems,String healthState,String coverageStatus,
                   boolean timelineIncluded,List<Span> timeline) {
        public Service { assets=List.copyOf(assets);timeline=List.copyOf(timeline); }
    }
    record Snapshot(UUID tenantId,UUID actorId,long permissionVersion,Instant observedAt,Instant monthStart,
                    Instant timelineStart,String mappingSha256,List<Service> services,ServiceAvailabilityAggregate.Result monthlySummary) {
        public Snapshot {
            new Access(tenantId,actorId,permissionVersion);Objects.requireNonNull(observedAt);
            if(monthStart==null||monthStart.isAfter(observedAt)||!observedAt.minusSeconds(86400).equals(timelineStart)
                ||mappingSha256==null||!mappingSha256.matches("[0-9a-f]{64}"))throw invalid();
            services=List.copyOf(services);Objects.requireNonNull(monthlySummary);
            if(monthlySummary.serviceCount()!=services.size())throw invalid();
            if(services.isEmpty()||services.size()>100||services.stream().map(Service::id).distinct().count()!=services.size())throw invalid();
        }
    }
    Snapshot read(Access access);

    static Snapshot evaluate(Access access,Instant observedAt,String mappingSha256,
                             List<Definition> definitions,Map<UUID,AssetEvidence> evidence) {
        var month=BusinessDates.localDate(observedAt).withDayOfMonth(1).atStartOfDay(BusinessDates.ZONE).toInstant();
        var timelineStart=observedAt.minusSeconds(86400);
        var window=new Interval(month.isBefore(timelineStart)?month:timelineStart,observedAt);
        if(definitions==null||definitions.isEmpty()||definitions.size()>100||definitions.stream().map(Definition::id).distinct().count()!=definitions.size())throw invalid();
        var services=new ArrayList<Service>();var evaluations=new ArrayList<SlaAvailabilityAndCompensation.ServiceEvaluation>();int totalSpans=0;
        for(var definition:definitions.stream().sorted(Comparator.comparing(Definition::id)).toList()) {
            var assets=new ArrayList<Asset>();var inputs=new ArrayList<Evidence>();
            var active=new HashSet<UUID>();var high=new HashSet<UUID>();
            for(var id:definition.assetIds()) {
                var item=evidence.get(id);if(item==null||!id.equals(item.asset().id()))throw invalid();
                assets.add(item.asset());inputs.add(item.evidence());active.addAll(item.activeProblems());high.addAll(item.highProblems());
            }
            var result=ServiceObservationIntervals.evaluate(inputs,definition.failureMode(),definition.maintenanceMode(),window);
            BigDecimal pct=null;long available=0,effective=0,down=0,unknown=0,excluded=0;String coverage="EMPTY_MONTH_WINDOW";
            if(month.isBefore(observedAt)) {
                var monthWindow=new Interval(month,observedAt);
                var evaluation=SlaAvailabilityAndCompensation.evaluateServiceEvidence(definition.id().toString(),definition.name(),definition.targetPct(),monthWindow,
                    result.maintenance(),result.unavailable(),result.unknown(),List.of(),result.healthy());
                evaluations.add(evaluation);
                pct=evaluation.service().verifiedAvailabilityPct(); available=evaluation.knownAvailableMillis();effective=evaluation.effectiveServiceMillis();
                var included=IntervalSets.subtract(List.of(monthWindow),result.maintenance());
                down=IntervalSets.duration(IntervalSets.intersection(result.unavailable(),included)).toMillis();
                unknown=IntervalSets.duration(IntervalSets.intersection(result.unknown(),included)).toMillis();
                excluded=IntervalSets.duration(IntervalSets.clip(result.maintenance(),monthWindow)).toMillis();
                coverage=included.isEmpty()?"NO_EFFECTIVE_WINDOW":effective==0?"NO_OBSERVATIONS":pct==null?"PARTIAL":"SUFFICIENT";
            }
            String state=pct==null||definition.targetPct()==null?"UNKNOWN":pct.compareTo(definition.targetPct())<0?"NOT_MET":!high.isEmpty()?"WARNING":"MET";
            boolean included=services.size()<8;
            var spans=included?timeline(result,inputs,new Interval(timelineStart,observedAt)):List.<Span>of();
            totalSpans+=spans.size();if(totalSpans>10000)throw new DomainException(422,"SERVICE_OBSERVATION_LIMIT","服务时间线超过读取上限");
            services.add(new Service(definition.id(),definition.name(),assets,definition.failureMode(),definition.maintenanceMode(),definition.targetPct(),pct,
                available,effective,down,unknown,excluded,active.size(),high.size(),state,coverage,included,spans));
        }
        return new Snapshot(access.tenantId(),access.actorId(),access.permissionVersion(),observedAt,month,timelineStart,mappingSha256,services,month.equals(observedAt)?new ServiceAvailabilityAggregate.Result(null,0,0,services.size(),0,"EMPTY_MONTH_WINDOW"):ServiceAvailabilityAggregate.calculate(evaluations));
    }
    private static DomainException invalid(){return new DomainException(503,"SERVICE_OBSERVATION_INVALID","服务定义或观测证据无效");}
}
