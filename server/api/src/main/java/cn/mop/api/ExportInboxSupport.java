package cn.mop.api;
import cn.mop.domain.DomainException;
import cn.mop.domain.assetexport.*;
import java.util.*;
import static cn.mop.api.ApiResponse.map;

final class ExportInboxSupport {
    final boolean enabled;final boolean configured;final ExportInboxRepository repository;final AssetExportPublicationSupport publication;
    ExportInboxSupport(boolean enabled,boolean configured,ExportInboxRepository repository,AssetExportPublicationSupport publication){this.enabled=enabled;this.configured=configured;this.repository=repository;this.publication=publication;}
    static ExportInboxSupport disabled(){return new ExportInboxSupport(false,false,null,null);}
    Map<String,Object> status(){List<String> reasons=!enabled?List.of("EXPORT_INBOX_DISABLED"):!configured||repository==null||publication==null||!publication.enabled()?List.of("EXPORT_INBOX_CONFIG_INVALID"):repository.readinessProblems();return map("enabled",enabled,"ready",enabled&&reasons.isEmpty(),"reasons",reasons,"external_delivery",false);}
    void requireEnabled(){if(!enabled)throw new DomainException(422,"CONFIG_PENDING","当前环境未启用导出消息");if(!Boolean.TRUE.equals(status().get("ready")))throw new DomainException(503,"UPSTREAM_UNAVAILABLE","已启用的导出消息库或配置未就绪");publication.requireConfigured();}
    void beforePublication(){if(enabled)requireEnabled();}
    void record(cn.mop.domain.assetexport.AssetExportJobRepository.Access a,AssetExportPublicationRepository.Publication p){if(enabled)repository.recordPublished(a,p);}
}
