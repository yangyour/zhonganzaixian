package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.configuration.RuntimeConfigurationRepository;
import java.math.BigDecimal;
import java.util.*;
import static cn.mop.api.ApiResponse.map;

/** Only a safe presentation policy is exposed to asset readers; no publisher identity or arbitrary settings. */
public final class MonitoringRefreshPolicy {
    public static final String KEY="monitor.page_refresh_sec";
    private final RuntimeConfigurationRepository repository;
    private final RuntimeConfigurationCatalog catalog;
    public MonitoringRefreshPolicy(RuntimeConfigurationRepository repository,RuntimeConfigurationCatalog catalog){this.repository=repository;this.catalog=catalog;}
    public Map<String,Object> read(Membership access,String trace){
        var result=repository.monitoringRefresh(new RuntimeConfigurationRepository.Access(access.tenantId(),access.userId(),access.permissionVersion(),trace));
        if(result==null||!access.tenantId().equals(result.get("tenant_id"))||!KEY.equals(result.get("configuration_key"))||!"postgresql".equals(result.get("source")))throw new DomainException(503,"UPSTREAM_UNAVAILABLE","刷新配置来源不符合当前授权范围");
        String status=String.valueOf(result.get("status")),hash=catalog.hash(KEY);Map<?,?> release=result.get("selected_release") instanceof Map<?,?> r?r:null;
        Integer seconds=null;String source="RUNTIME_BLOCKED",reason=status;
        if(Set.of("UNCONFIGURED","SCHEDULED").contains(status)&&release==null){Object registered=catalog.entry(KEY).get("value");if(!(registered instanceof Number n)||n.intValue()!=30)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","合同刷新默认值未通过核对");seconds=30;source="CONTRACT_DEFAULT";reason=null;}
        else if("ACTIVE".equals(status)&&release!=null){
            if(!hash.equals(release.get("definition_hash")))reason="DEFINITION_CHANGED";
            else try{Object value=result.get("effective_value");if(!(value instanceof Number))throw new IllegalArgumentException();int n=new BigDecimal(value.toString()).intValueExact();if(n<1||n>3600)throw new IllegalArgumentException();seconds=n;source="RUNTIME_PUBLICATION";reason=null;}catch(RuntimeException invalid){reason="INVALID_VALUE";}
        }else if(!Set.of("DISABLED","EXPIRED").contains(status)||release==null)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","刷新配置状态未通过核对");
        return map("tenant_id",access.tenantId(),"actor_id",access.userId(),"permission_version",access.permissionVersion(),"configuration_key",KEY,"interval_seconds",seconds,"auto_refresh_enabled",seconds!=null,"source",source,"reason",reason,"publication_id",release==null?null:release.get("id"),"publication_version",release==null?null:release.get("version"),"checked_at",result.get("observed_at"),"expires_at",release==null?null:release.get("expires_at"),"definition_hash",hash,"production_approved",false);
    }
}
