package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.assetmetadata.*;
import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository.*;
import cn.mop.domain.warranty.WarrantyDateAssessment;
import cn.mop.domain.worklog.ManualWorklog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static cn.mop.api.ApiResponse.*;
/** Bounded recorded date-window search, not a monitoring source, coverage guarantee or queued reminder. */
@RestController @RequestMapping("/api/v1/assets/date-assessments") @Profile("remote_test")
public final class AssetDateQueryController {
    private final PlatformRepository platform;private final ManualAssetMetadataRepository metadata;private final Clock clock;
    private static final Set<String> KEYS=Set.of("date_field","window","name","site_id","page_size","before_created_at","before_id","expected_reference_date");
    @Autowired public AssetDateQueryController(PlatformRepository platform,ManualAssetMetadataRepository metadata){this(platform,metadata,Clock.systemUTC());}
    AssetDateQueryController(PlatformRepository platform,ManualAssetMetadataRepository metadata,Clock clock){this.platform=platform;this.metadata=metadata;this.clock=clock;}
    @GetMapping Object search(HttpServletRequest request){
        Access access=access(request);if(request.getQueryString()!=null&&request.getQueryString().getBytes(StandardCharsets.UTF_8).length>4096)throw invalid();Map<String,String> query=new HashMap<>();request.getParameterMap().forEach((key,values)->{if(!KEYS.contains(key)||values.length!=1||values[0]==null||values[0].length()>400)throw invalid();query.put(key,values[0]);});
        AssetDateFilter.Field field;AssetDateFilter.Window window;try{field=Arrays.stream(AssetDateFilter.Field.values()).filter(f->f.key().equals(query.get("date_field"))).findFirst().orElseThrow();window=AssetDateFilter.Window.valueOf(query.get("window"));}catch(RuntimeException failure){throw invalid();}
        String size=query.getOrDefault("page_size","15");if(!Set.of("10","15","25","50","100").contains(size))throw invalid();int limit=Integer.parseInt(size);
        String rawTime=query.get("before_created_at"),rawId=query.get("before_id"),rawDate=query.get("expected_reference_date");if((rawTime==null)!=(rawId==null)||(rawId==null)!=(rawDate==null))throw invalid();Instant before=null;String beforeId=null;
        Instant observed=clock.instant().truncatedTo(ChronoUnit.MILLIS);LocalDate reference=WarrantyDateAssessment.referenceDate(observed);
        if(rawTime!=null){before=ManualWorklog.parseInstant(rawTime);if(!rawId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();beforeId=UUID.fromString(rawId).toString();if(!reference.equals(ManualAssetMetadata.parseDate(rawDate)))throw new DomainException(409,"DATE_REFERENCE_CHANGED","服务端日期已变化，请从第一页重新查询；不能沿用旧日期窗口");}
        Map<String,String> text=new TreeMap<>();for(String key:List.of("name","site_id"))if(query.containsKey(key))text.put(key,query.get(key));ManualAssetFilter filter=ManualAssetFilter.fromQuery(text);var dates=new AssetDateFilter(field,window,reference);
        List<Current> all=metadata.searchDates(access,filter,dates,limit+1,before,beforeId);boolean more=all.size()>limit;List<Current> items=all.subList(0,Math.min(limit,all.size()));List<Map<String,Object>> output=new ArrayList<>();for(Current item:items){Asset a=item.snapshot().asset();if(!"manual".equals(a.source())||!dates.matches(item.snapshot().metadata()==null?null:field==AssetDateFilter.Field.WARRANTY_EXPIRY?item.snapshot().metadata().warrantyExpiry():item.snapshot().metadata().overdueServiceDate()))throw new IllegalStateException("Date window repository mismatch");output.add(map("asset_id",a.id(),"name",a.name(),"created_at",a.createdAt(),"assessment",AssetDateAssessmentController.projection(item,access.tenantId(),a.id(),observed)));}
        Asset tail=more?items.get(items.size()-1).snapshot().asset():null;
        return success(map("items",output,"has_more",more,"next_before_created_at",tail==null?null:tail.createdAt(),"next_before_id",tail==null?null:tail.id(),"next_expected_reference_date",tail==null?null:reference,"date_field",field.key(),"window",window.name(),"filters",text,"reference_date",reference,"observed_at",observed,"timezone","Asia/Shanghai","source","postgresql","scope","CURRENT_TENANT_INTERNAL_MANUAL_DATE_FILTER","page_size_limit",100,"pagination_consistency","CURRENT_REQUEST_SNAPSHOT_ONLY","metadata_change_semantics","NOT_FROZEN_ACROSS_PAGES"),request);
    }
    private Access access(HttpServletRequest request){if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object tenant=request.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String scope)||scope.isBlank())throw expired();String actor=request.getUserPrincipal().getName();Membership member=platform.membership(actor,scope).orElseThrow(AssetDateQueryController::expired);if(!member.active()||member.permissionVersion()<1)throw expired();if(!member.capabilities().contains("asset_dates:read"))throw new DomainException(403,"ACTION_FORBIDDEN","日期筛选仅开放当前授权内部人员");return new Access(actor,scope,member.permissionVersion(),trace(request));}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","日期字段/窗口必须明确，筛选仅名称与站点；有界分页游标须同时包含创建时间、资产ID及服务端参考日");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
