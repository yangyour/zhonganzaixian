package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.dashboard.ServiceObservationReader;
import cn.mop.domain.dashboard.ServiceObservationIntervals.*;
import cn.mop.domain.monitoring.MonitoringEventPipeline.Severity;
import cn.mop.domain.time.BusinessDates;
import cn.mop.domain.time.IntervalSets.Interval;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.security.MessageDigest;

/** Bounded, read-only RR projection using existing tenant assets, faults and verified recovery proof. */
public final class PostgresServiceObservationReader implements ServiceObservationReader {
    private final DataSource source;private final PostgresPlatformRepository platform;
    private final boolean enabled;private final Function<UUID,List<Definition>> definitions;
    public PostgresServiceObservationReader(DataSource source,PostgresPlatformRepository platform,boolean enabled,
                                            Function<UUID,List<Definition>> definitions) {
        this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);
        this.enabled=enabled;this.definitions=Objects.requireNonNull(definitions);
    }
    @Override public Snapshot read(Access access) {
        if(!enabled)throw new DomainException(503,"SERVICE_OBSERVATION_DISABLED","服务观测读取尚未启用");
        Objects.requireNonNull(access);platform.requireStandaloneRead();
        var configured=configuration(access.tenantId());
        var ids=configured.stream().flatMap(d->d.assetIds().stream()).distinct().sorted().toList();
        if(ids.size()>1000)throw limit();
        long started=System.nanoTime();
        try(var c=source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try {
                authorize(c,access);
                Instant observed;
                try(var s=statement(c,"SELECT date_trunc('milliseconds',clock_timestamp()) AS observed_at,current_user AS actor,session_user AS session_actor")) {
                    try(var r=s.executeQuery()) {
                        if(!r.next()||!"mop_test_runtime".equals(r.getString("actor"))||!"mop_test_runtime".equals(r.getString("session_actor")))throw unavailable();
                        observed=time(r,"observed_at");
                    }
                }
                var month=BusinessDates.localDate(observed).withDayOfMonth(1).atStartOfDay(BusinessDates.ZONE).toInstant();
                var timeline=observed.minusSeconds(86400);var from=month.isBefore(timeline)?month:timeline;
                var assets=new LinkedHashMap<UUID,Asset>();
                try(var s=statement(c,"SELECT id,name,version FROM public.mop_asset WHERE tenant_id=?::uuid AND id=ANY(?::uuid[]) ORDER BY id FOR SHARE")) {
                    bindAssets(c,s,access,ids);
                    try(var r=s.executeQuery()){while(r.next()) {budget(started);var id=UUID.fromString(r.getString("id"));if(!ids.contains(id)||assets.put(id,new Asset(id,r.getString("name"),r.getLong("version")))!=null)throw unavailable();}}
                }
                if(assets.size()!=ids.size())throw new DomainException(404,"RESOURCE_NOT_FOUND","服务映射资源不存在");
                var faults=new HashMap<UUID,List<Fault>>();var unknown=new HashMap<UUID,List<Interval>>();
                var maintenance=new HashMap<UUID,List<Interval>>();var active=new HashMap<UUID,Set<UUID>>();var high=new HashMap<UUID,Set<UUID>>();
                try(var s=statement(c,"SELECT id,asset_id,problem_at,monitor_state,severity,suppression_state,sla_unknown_marked FROM public.mop_monitoring_event WHERE tenant_id=?::uuid AND asset_id=ANY(?::uuid[]) AND problem_at<? ORDER BY problem_at,id LIMIT 10001")) {
                    bindAssets(c,s,access,ids);instant(s,3,observed);
                    try(var r=s.executeQuery()){int count=0;while(r.next()) {
                        budget(started);if(++count>10000)throw limit();
                        var id=UUID.fromString(r.getString("id"));var asset=UUID.fromString(r.getString("asset_id"));
                        if(!assets.containsKey(asset))throw unavailable();
                        var start=time(r,"problem_at");var state=r.getString("monitor_state");
                        if(!Set.of("PROBLEM","RECOVERED").contains(state))throw unavailable();
                        var severity=Severity.valueOf(r.getString("severity"));var suppression=r.getString("suppression_state");
                        if(!Set.of("NONE","FLAP_SILENCED","DEPENDENCY_SUPPRESSED","MAINTENANCE_SILENCED","INFO_DEFAULT_SILENT").contains(suppression))throw unavailable();
                        var proof=PostgresMonitoringRecoveryEvidence.read(c,access.tenantId().toString(),id.toString());
                        var end=proof.map(p->p.recoveredAt()).orElse(observed);
                        if(end.isAfter(observed)||end.isBefore(start))throw unavailable();
                        if(!end.isAfter(from))continue;
                        if(start.isBefore(from))start=from;
                        if(start.isBefore(end)) {
                            var interval=new Interval(start,end);
                            if(r.getBoolean("sla_unknown_marked")||("RECOVERED".equals(state)&&proof.isEmpty()))unknown.computeIfAbsent(asset,k->new ArrayList<>()).add(interval);
                            else faults.computeIfAbsent(asset,k->new ArrayList<>()).add(new Fault(interval,severity));
                        }
                        if("PROBLEM".equals(state)&&"NONE".equals(suppression)) {
                            active.computeIfAbsent(asset,k->new HashSet<>()).add(id);
                            if(severity==Severity.HIGH||severity==Severity.DISASTER)high.computeIfAbsent(asset,k->new HashSet<>()).add(id);
                        }
                    }}
                }
                try(var s=statement(c,"SELECT asset_id,starts_at,ends_at FROM public.mop_maintenance_window WHERE tenant_id=?::uuid AND asset_id=ANY(?::uuid[]) AND sla_exclusion_confirmed AND status<>'CANCELLED' AND starts_at<? AND ends_at>? ORDER BY starts_at,id LIMIT 10001")) {
                    bindAssets(c,s,access,ids);instant(s,3,observed);instant(s,4,from);
                    try(var r=s.executeQuery()){int count=0;while(r.next()) {
                        budget(started);if(++count>10000)throw limit();var id=UUID.fromString(r.getString("asset_id"));if(!assets.containsKey(id))throw unavailable();
                        maintenance.computeIfAbsent(id,k->new ArrayList<>()).add(new Interval(time(r,"starts_at"),time(r,"ends_at")));
                    }}
                }
                var evidence=new HashMap<UUID,AssetEvidence>();
                for(var id:ids)evidence.put(id,new AssetEvidence(assets.get(id),new Evidence(faults.getOrDefault(id,List.of()),unknown.getOrDefault(id,List.of()),maintenance.getOrDefault(id,List.of()),List.of()),active.getOrDefault(id,Set.of()),high.getOrDefault(id,Set.of())));
                var result=ServiceObservationReader.evaluate(access,observed,fingerprint(configured),configured,evidence);
                budget(started);authorize(c,access);if(!configured.equals(configuration(access.tenantId())))throw unavailable();
                c.commit();return result;
            }catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}
        }catch(DomainException expected){throw expected;}catch(SQLException|IllegalArgumentException failure){throw unavailable();}
    }
    private List<Definition> configuration(UUID tenant) {
        var configured=definitions.apply(tenant);
        if(configured==null||configured.isEmpty())throw new DomainException(503,"SERVICE_MAPPING_PENDING","当前租户尚未配置服务与资产映射");
        if(configured.size()>100)throw limit();return configured.stream().sorted(Comparator.comparing(Definition::id)).toList();
    }
    private static void authorize(Connection c,Access a)throws SQLException {
        try(var s=statement(c,"SELECT m.permission_version,m.role_code FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active FOR SHARE OF m,u")) {
            s.setString(1,a.tenantId().toString());s.setString(2,a.actorId().toString());
            try(var r=s.executeQuery()){if(!r.next()||r.getLong("permission_version")!=a.permissionVersion())throw new DomainException(401,"SESSION_EXPIRED","当前成员权限已失效");if(!Set.of("msp","padmin").contains(r.getString("role_code")))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色不能读取服务观测");}
        }
        if(!PostgresAuthorization.capabilities(c,a.actorId().toString(),a.tenantId().toString()).containsAll(List.of("dashboard:read","assets:read","tickets:read","sla_scenarios:read")))throw new DomainException(403,"ACTION_FORBIDDEN","缺少服务观测读取权限");
    }
    private static String fingerprint(List<Definition> definitions) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new ObjectMapper().writeValueAsBytes(definitions)));}
        catch(Exception failure){throw unavailable();}
    }
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static void bindAssets(Connection c,PreparedStatement s,Access a,List<UUID> ids)throws SQLException{s.setString(1,a.tenantId().toString());s.setArray(2,c.createArrayOf("uuid",ids.toArray()));}
    private static void instant(PreparedStatement s,int index,Instant value)throws SQLException{s.setObject(index,OffsetDateTime.ofInstant(value,ZoneOffset.UTC));}
    private static Instant time(ResultSet r,String column)throws SQLException{var value=r.getObject(column,OffsetDateTime.class);if(value==null)throw unavailable();return value.toInstant();}
    private static void budget(long started){if(System.nanoTime()-started>30_000_000_000L)throw unavailable();}
    private static DomainException unavailable(){return new DomainException(503,"SERVICE_OBSERVATION_UNAVAILABLE","服务观测读取失败，未返回部分或推测数据");}
    private static DomainException limit(){return new DomainException(422,"SERVICE_OBSERVATION_LIMIT","服务观测超过单次读取上限，未截断结果");}
}
