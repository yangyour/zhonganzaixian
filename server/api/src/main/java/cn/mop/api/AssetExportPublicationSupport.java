package cn.mop.api;
import cn.mop.domain.DomainException;
import cn.mop.domain.assetexport.*;
import java.util.*;

public final class AssetExportPublicationSupport {
    private final boolean enabled;private final AssetExportObjectStore store;private final AssetExportDownloadGrant grants;
    AssetExportPublicationSupport(boolean enabled,AssetExportObjectStore store,AssetExportDownloadGrant grants){this.enabled=enabled;this.store=store;this.grants=grants;}
    public boolean enabled(){return enabled;}
    public Map<String,Object> status(){
        boolean configured=store!=null&&grants!=null,ready=false;
        if(enabled&&configured)try{ready=store.ready();}catch(RuntimeException ignored){}
        return ApiResponse.map("enabled",enabled,"ready",ready,"configured",configured,"provider",configured?"minio":null,
            "reasons",ready?List.of():List.of(!enabled?"EXPORT_PUBLICATION_DISABLED":!configured?"EXPORT_PUBLICATION_CONFIG_INVALID":"EXPORT_STORAGE_UNAVAILABLE"));
    }
    void requireConfigured(){if(!enabled)throw new DomainException(422,"CONFIG_PENDING","导出发布尚未启用");if(store==null||grants==null)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","导出存储或签名私有配置未就绪");}
    AssetExportObjectStore store(){requireConfigured();return store;}
    AssetExportDownloadGrant grants(){requireConfigured();return grants;}
}
