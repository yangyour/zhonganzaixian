package cn.mop.api;

import cn.mop.adapters.monitoring.*;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.*;
import cn.mop.domain.monitoring.MonitoringObjectBinding.*;
import cn.mop.domain.monitoring.MonitoringObjectBindingRepository.Source;
import cn.mop.persistence.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.util.*;
import java.util.function.Function;

@Configuration
@Profile("remote_test")
public class MonitoringObjectBindingConfiguration {
 private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 record Definition(String provider,String reference,String endpoint,String tokenEnv,ZabbixObjectIdentityReader.Tags tags){@Override public String toString(){return "MappingSourceDefinition[private configuration redacted]";}}

    @Bean
    MonitoringObjectBindingRepository monitoringObjectBindingRepository(
        PlatformRepository platform,
        ObjectMapper json,
        Environment env,
        javax.sql.DataSource dataSource
    ) {
        if (!(platform instanceof PostgresPlatformRepository pg)) {
            throw new IllegalStateException("Mapping journal requires designated PostgreSQL");
        }
        return new PostgresMonitoringObjectBindingRepository(
            pg,
            json,
            policies(env.getProperty("MOP_MONITORING_MAPPING_POLICIES")),
            sources(env),
            new cn.mop.persistence.PostgresMonitoringEventCenterRepository(dataSource, pg)
        );
    }
 static Function<String,Policy> policies(String raw){
  if(raw==null||raw.isBlank())return tenant->new Policy(null,null,null,null,null);
  try{var root=object(raw);var out=new HashMap<String,Policy>();var entries=root.fields();while(entries.hasNext()){
   var entry=entries.next();uuid(entry.getKey());var p=JSON.treeToValue(entry.getValue(),Policy.class);
   var canonical=new TreeMap<String,Object>();canonical.put("pools",p.pools()==null?null:p.pools().stream().map(UUID::toString).sorted().toList());canonical.put("reviewers",p.reviewers()==null?null:p.reviewers().stream().map(UUID::toString).sorted().toList());canonical.put("self_approval_allowed",p.selfApprovalAllowed());canonical.put("verification_ttl_seconds",p.verificationTtlSeconds());canonical.put("reference",p.reference());
   String ref=p.reference()==null?null:p.reference()+":"+hash(JSON.writeValueAsBytes(canonical));out.put(entry.getKey(),new Policy(ref,p.pools(),p.reviewers(),p.selfApprovalAllowed(),p.verificationTtlSeconds()));
  }var frozen=Map.copyOf(out);return tenant->frozen.getOrDefault(tenant,new Policy(null,null,null,null,null));}catch(Exception e){throw new IllegalStateException("Invalid explicit mapping policy configuration");}
 }

    static Source sources(Environment env) {
        var definitions = new HashMap<UUID, Definition>();
        var hashes = new HashMap<UUID, String>();
        String raw = env.getProperty("MOP_MONITORING_MAPPING_SOURCES");
        if (raw != null && !raw.isBlank()) {
            try {
                var entries = object(raw).fields();
                while (entries.hasNext()) {
                    var entry = entries.next();
                    UUID pool = uuid(entry.getKey());
                    var d = JSON.treeToValue(entry.getValue(), Definition.class);
                    if (
                        !"ZABBIX_7".equals(d.provider()) ||
                        d.reference() == null ||
                        !d.reference().matches("[A-Za-z0-9._:-]{1,128}") ||
                        (d.tokenEnv() != null && !d.tokenEnv().matches("[A-Z][A-Z0-9_]{0,127}"))
                    ) {
                        throw new IllegalArgumentException();
                    }
                    if (d.endpoint() != null) {
                        ZabbixVersionProtocol.requireLoopbackEndpoint(java.net.URI.create(d.endpoint()));
                    }
                    definitions.put(pool, d);
                    var canonical = new TreeMap<String, Object>();
                    canonical.put("provider", d.provider());
                    canonical.put("reference", d.reference());
                    canonical.put("endpoint", d.endpoint());
                    canonical.put("token_env", d.tokenEnv());
                    canonical.put(
                        "tags",
                        d.tags() == null
                            ? null
                            : Map.of("pool", d.tags().pool(), "tenant", d.tags().tenant(), "asset", d.tags().asset())
                    );
                    hashes.put(pool, hash(JSON.writeValueAsBytes(canonical)));
                }
            } catch (Exception e) {
                throw new IllegalStateException("Invalid explicit mapping source configuration");
            }
        }
        var frozen = Map.copyOf(definitions);
        var frozenHashes = Map.copyOf(hashes);
        return new Source() {
            public String provider(UUID pool) {
                var d = frozen.get(pool);
                return d == null ? null : d.provider();
            }

            public String definitionSha256(UUID pool) {
                var d = frozen.get(pool);
                return ready(d) ? frozenHashes.get(pool) : null;
            }

            private boolean ready(Definition d) {
                return (
                    d != null &&
                    d.endpoint() != null &&
                    d.tokenEnv() != null &&
                    d.tags() != null &&
                    env.getProperty(d.tokenEnv()) != null
                );
            }

            public MonitoringObjectBindingRepository.FetchedEvent readEvent(
                MonitoringObjectMapping.Key key,
                String eventId
            ) {
                var d = frozen.get(key.poolId());
                if (!ready(d)) {
                    throw new DomainException(503, "MONITORING_MAPPING_SOURCE_PENDING", "来源读取尚未配置");
                }
                try {
                    return new ZabbixMappedEventReader(
                        key.poolId(),
                        new ZabbixReadClient(d.endpoint(), env.getProperty(d.tokenEnv()))
                    ).read(key, eventId);
                } catch (DomainException e) {
                    throw e;
                } catch (Exception e) {
                    throw new DomainException(503, "MAPPING_CAPTURE_SOURCE_UNAVAILABLE", "来源事件读取暂不可用");
                }
            }

            public MappedSourcePage.Fetched readPage(MonitoringObjectMapping.Key key, MappedSourcePage.Window window) {
                var d = frozen.get(key.poolId());
                if (!ready(d)) {
                    throw new DomainException(503, "MONITORING_MAPPING_SOURCE_PENDING", "来源分页读取尚未配置");
                }
                return new ZabbixMappedEventPageReader(
                    key.poolId(),
                    new ZabbixReadClient(d.endpoint(), env.getProperty(d.tokenEnv()))
                ).read(key, window);
            }

            public SourceMetadata verify(MonitoringObjectMapping.Key key) {
                var d = frozen.get(key.poolId());
                if (!ready(d)) {
                    throw new DomainException(
                        503,
                        "MONITORING_MAPPING_SOURCE_PENDING",
                        "来源端点、秘密引用或明确归属标签尚未配置"
                    );
                }
                try {
                    var row = new ZabbixObjectIdentityReader(
                        key.poolId(),
                        d.tags(),
                        new ZabbixReadClient(d.endpoint(), env.getProperty(d.tokenEnv()))
                    ).read(key);
                    return new SourceMetadata(
                        key,
                        d.provider(),
                        row.sourceHostId(),
                        row.claimedPoolId(),
                        row.claimedTenantId(),
                        row.claimedAssetId(),
                        frozenHashes.get(key.poolId()),
                        row.metadataSha256()
                    );
                } catch (Exception e) {
                    throw new DomainException(
                        503,
                        "MONITORING_MAPPING_SOURCE_UNVERIFIED",
                        "未取得完整、唯一且匹配的来源归属元数据；未发布映射"
                    );
                }
            }
        };
    }
 private static JsonNode object(String raw)throws Exception{if(raw.length()>65536)throw new IllegalArgumentException();var value=JSON.readTree(raw);if(!value.isObject()||value.size()>100)throw new IllegalArgumentException();return value;}
 private static UUID uuid(String value){if(!MonitoringPresetRegistry.uuid(value))throw new IllegalArgumentException();return UUID.fromString(value);}
 private static String hash(byte[] value)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}
}
