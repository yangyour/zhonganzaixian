package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.configuration.RuntimeConfigurationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.map;

/** Explicit import into the estimator; no retention deletion, collector change or procurement action. */
public final class CapacityRuntimeInputs {
    private final RuntimeConfigurationRepository repository;
    private final RuntimeConfigurationCatalog catalog;
    private final ObjectMapper mapper;
    public CapacityRuntimeInputs(RuntimeConfigurationRepository repository,RuntimeConfigurationCatalog catalog,ObjectMapper mapper){this.repository=repository;this.catalog=catalog;this.mapper=mapper;}
    public Map<String,Object> read(Membership member,String trace){
        var rows=repository.capacityInputs(new RuntimeConfigurationRepository.Access(member.tenantId(),member.userId(),member.permissionVersion(),trace));
        if(rows==null||rows.size()!=RuntimeConfigurationRepository.CAPACITY_KEYS.size())throw unavailable();
        List<Map<String,Object>> items=new ArrayList<>();String checked=null;
        for(int i=0;i<rows.size();i++){
            var row=rows.get(i);String key=RuntimeConfigurationRepository.CAPACITY_KEYS.get(i),hash=catalog.hash(key);
            if(row==null||!key.equals(row.get("configuration_key"))||!member.tenantId().equals(row.get("tenant_id"))||!"postgresql".equals(row.get("source")))throw unavailable();
            String observed=String.valueOf(row.get("observed_at"));try{Instant.parse(observed);}catch(Exception invalid){throw unavailable();}
            if(checked!=null&&!checked.equals(observed))throw unavailable();checked=observed;
            String status=String.valueOf(row.get("status"));Map<?,?> release=row.get("selected_release") instanceof Map<?,?> r?r:null;
            if(!Set.of("ACTIVE","DISABLED","EXPIRED","UNCONFIGURED","SCHEDULED").contains(status)||Set.of("ACTIVE","DISABLED","EXPIRED").contains(status)!=(release!=null))throw unavailable();
            String value=null,inputValue=null;
            if("ACTIVE".equals(status)){
                if(!hash.equals(release.get("definition_hash")))status="DEFINITION_CHANGED";
                else try{
                    Object raw=row.get("effective_value");catalog.validate(key,mapper.valueToTree(raw));if(!(raw instanceof Number))throw new IllegalArgumentException();
                    BigDecimal number=new BigDecimal(raw.toString());value=number.stripTrailingZeros().toPlainString();BigDecimal converted=key.equals("capacity.headroom")?number.subtract(BigDecimal.ONE):number;
                    boolean days=key.startsWith("retention.");
                    if(days&&number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE))>0||key.equals("capacity.headroom")&&converted.compareTo(BigDecimal.ONE)>0||!converted.toPlainString().matches("(?:0|[1-9][0-9]{0,39})(?:\\.[0-9]{1,40})?"))status="INCOMPATIBLE_MODEL";
                    else inputValue=converted.stripTrailingZeros().toPlainString();
                }catch(RuntimeException invalid){status="INVALID_VALUE";value=null;}
            }
            items.add(map("configuration_key",key,"status",status,"value",value,"input_value",inputValue,"unit",catalog.entry(key).get("unit"),"definition_hash",hash,"publication_id",release==null?null:release.get("id"),"publication_version",release==null?null:release.get("version"),"expires_at",release==null?null:release.get("expires_at")));
        }
        return map("tenant_id",member.tenantId(),"actor_id",member.userId(),"permission_version",member.permissionVersion(),"checked_at",checked,"items",items,"scope","CURRENT_TENANT_CAPACITY_INPUTS","source","postgresql","customer_confirmation_status","UNVERIFIED","production_approved",false,"measurement_verified",false);
    }
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","容量配置来源未通过核对，未返回替代参数");}
}
