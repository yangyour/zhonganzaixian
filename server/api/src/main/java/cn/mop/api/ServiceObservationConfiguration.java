package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import cn.mop.domain.dashboard.ServiceObservationReader;
import cn.mop.domain.dashboard.ServiceObservationReader.Definition;
import cn.mop.domain.dashboard.ServiceObservationIntervals.*;
import cn.mop.persistence.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import javax.sql.DataSource;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
@Profile("remote_test")
public class ServiceObservationConfiguration {
    @Bean cn.mop.domain.dashboard.AssetReachability.Reader assetReachabilityReader(DataSource source,PlatformRepository repository){
        if(!(repository instanceof PostgresPlatformRepository platform))throw new IllegalStateException("Asset reachability requires designated PostgreSQL");
        return new PostgresAssetReachabilityReader(source,platform);
    }
    @Bean ServiceObservationReader serviceObservationReader(DataSource source,PlatformRepository repository,Environment environment) {
        if(!(repository instanceof PostgresPlatformRepository platform))throw new IllegalStateException("Service observations require designated PostgreSQL");
        boolean enabled=MonitoringEnrollmentConfiguration.flag(environment,"MOP_SERVICE_OBSERVATIONS_ENABLED");
        var definitions=parse(environment.getProperty("MOP_SERVICE_DEFINITIONS",""));
        return new PostgresServiceObservationReader(source,platform,enabled,tenant->definitions.getOrDefault(tenant,List.of()));
    }
    static Map<UUID,List<Definition>> parse(String raw) {
        if(raw==null||raw.isBlank())return Map.of();
        if(raw.length()>1_048_576)throw invalid();
        try {
            var mapper=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var root=mapper.readTree(raw);if(!root.isObject()||root.size()>100)throw invalid();
            var result=new HashMap<UUID,List<Definition>>();
            var fields=root.fields();
            while(fields.hasNext()) {
                var field=fields.next();var tenant=uuid(field.getKey());var array=field.getValue();
                if(!array.isArray()||array.size()>100)throw invalid();
                var definitions=new ArrayList<Definition>();var serviceIds=new HashSet<UUID>();var assetIds=new HashSet<UUID>();
                for(var node:array) {
                    var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);
                    if(!node.isObject()||!keys.equals(Set.of("id","name","asset_ids","failure_mode","maintenance_mode","target_pct")))throw invalid();
                    var id=uuid(string(node,"id"));if(!serviceIds.add(id))throw invalid();
                    var assetsNode=node.get("asset_ids");if(!assetsNode.isArray())throw invalid();
                    var assets=new ArrayList<UUID>();for(var value:assetsNode){if(!value.isTextual())throw invalid();assets.add(uuid(value.textValue()));}
                    assetIds.addAll(assets);if(assetIds.size()>1000)throw invalid();
                    var target=node.get("target_pct");if(!target.isNull()&&!target.isNumber())throw invalid();
                    definitions.add(new Definition(id,string(node,"name"),assets,FailureMode.valueOf(string(node,"failure_mode")),MaintenanceMode.valueOf(string(node,"maintenance_mode")),target.isNull()?null:target.decimalValue()));
                }
                result.put(tenant,List.copyOf(definitions));
            }
            return Map.copyOf(result);
        }catch(Exception failure){throw invalid();}
    }
    private static String string(JsonNode node,String field){var value=node.get(field);if(value==null||!value.isTextual())throw invalid();return value.textValue();}
    private static UUID uuid(String value){var parsed=UUID.fromString(value);if(!parsed.toString().equals(value))throw invalid();return parsed;}
    private static IllegalStateException invalid(){return new IllegalStateException("MOP_SERVICE_DEFINITIONS must contain explicit valid tenant service mappings; configuration content withheld");}
}
