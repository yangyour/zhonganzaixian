package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.assetmetadata.*;
import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository.*;
import cn.mop.domain.warranty.WarrantyDateAssessment;
import cn.mop.domain.warranty.WarrantyDateAssessment.DateResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;
import static cn.mop.api.ApiResponse.*;
/** Real authorized recorded metadata, with separately labelled calendar arithmetic; no lifecycle mutation. */
@RestController @RequestMapping("/api/v1/assets/{id}/manual-metadata/date-assessment") @Profile("remote_test")
public final class AssetDateAssessmentController {
    private final PlatformRepository platform;private final ManualAssetMetadataRepository metadata;private final Clock clock;
    @Autowired public AssetDateAssessmentController(PlatformRepository platform,ManualAssetMetadataRepository metadata){this(platform,metadata,Clock.systemUTC());}
    AssetDateAssessmentController(PlatformRepository platform,ManualAssetMetadataRepository metadata,Clock clock){this.platform=platform;this.metadata=metadata;this.clock=clock;}
    @GetMapping Object get(@PathVariable String id,HttpServletRequest request){
        Access access=access(request);if(!request.getParameterMap().isEmpty()||id==null||!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();
        String assetId=UUID.fromString(id).toString();Current current=metadata.current(access,assetId);Snapshot snapshot=current.snapshot();
        if(!snapshot.asset().id().equals(assetId)||!snapshot.asset().tenantId().equals(access.tenantId()))throw new IllegalStateException("Metadata projection scope mismatch");
        Instant observed=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);ManualAssetMetadata facts=snapshot.metadata();var assessment=WarrantyDateAssessment.assess(WarrantyDateAssessment.referenceDate(observed),facts==null?null:facts.warrantyExpiry(),facts==null?null:facts.overdueServiceDate());
        return success(map("asset_id",assetId,"tenant_id",access.tenantId(),"asset_version",snapshot.asset().version(),"metadata_version",snapshot.metadataVersion(),"observed_at",observed,"as_of_date",assessment.asOfDate(),"timezone",assessment.timezone(),"policy_version",assessment.policyVersion(),"scope",assessment.scope(),"source","postgresql","recorded_dates_source","MANUAL_ASSET_METADATA","verification_status",facts==null?null:"SELF_REPORTED","coverage_verified",false,"warranty_expiry",dto(assessment.warrantyExpiry()),"overdue_service_date",dto(assessment.overdueServiceDate()),"warranty_start",null,"warranty_provider",null,"hazard_lookup_status","NOT_IMPLEMENTED","notification_lookup_status","NOT_IMPLEMENTED","actions_this_request",map("hazard_creation",assessment.hazardCreationStatus(),"notification",assessment.notificationStatus(),"asset_updated",false)),request);
    }
    private static Map<String,Object> dto(DateResult value){return map("recorded_date",value.recordedDate(),"days_until",value.daysUntil(),"position",value.position().name(),"within_7_days",value.within7Days(),"within_30_days",value.within30Days(),"within_90_days",value.within90Days(),"in_reference_quarter",value.inReferenceQuarter());}
    private Access access(HttpServletRequest request){if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object tenant=request.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String scope)||scope.isBlank())throw expired();String actor=request.getUserPrincipal().getName();Membership member=platform.membership(actor,scope).orElseThrow(AssetDateAssessmentController::expired);if(!member.active()||member.permissionVersion()<1)throw expired();if(!Capabilities.forRole(member.role()).contains("asset_dates:read"))throw new DomainException(403,"ACTION_FORBIDDEN","记录日期对照仅开放当前授权内部人员");return new Access(actor,scope,member.permissionVersion(),trace(request));}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","日期对照只接受资产路径，参考日由服务端按Asia/Shanghai取值，不接受query覆盖");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
