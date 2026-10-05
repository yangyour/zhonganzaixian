package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.assetimport.source.AssetImportSchema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Authorized blank template download. Does not create import sources, operations or assets. */
@RestController @RequestMapping("/api/v1") @Profile("remote_test")
public final class AssetImportTemplateController {
    private final PlatformRepository platform;
    public AssetImportTemplateController(PlatformRepository platform){this.platform=platform;}
    @GetMapping("/assets/import-template") ResponseEntity<byte[]> download(HttpServletRequest request){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();
        Object value=request.getSession(false).getAttribute("mop.tenant_id");if(!(value instanceof String tenant))throw expired();
        Membership member=platform.membership(request.getUserPrincipal().getName(),tenant).orElseThrow(AssetImportTemplateController::expired);if(!member.active())throw expired();
        if(!member.capabilities().contains("assets:write"))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无资产导入模板下载权限");
        if(!request.getParameterMap().isEmpty())throw new DomainException(400,"VALIDATION_ERROR","标准模板下载不接受查询参数");
        byte[] content=AssetImportTemplateFile.read();
        // The shared security filter already sets no-store; do not append a duplicate header.
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(AssetImportTemplateFile.MEDIA_TYPE)).contentLength(content.length)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(AssetImportTemplateFile.NAME).build().toString())
            .header("X-Content-Type-Options","nosniff").header("X-MOP-Template-SHA256",AssetImportTemplateFile.SHA256)
            .header("X-MOP-Template-Schema",AssetImportSchema.VERSION).header("X-MOP-Business-Status","CONFIG_PENDING").body(content);
    }
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
