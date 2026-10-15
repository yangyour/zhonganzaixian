package cn.mop.api;

import cn.mop.domain.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static cn.mop.api.ApiResponse.*;

@RestController @RequestMapping("/api/v1") @Profile({"local_fixture","remote_test"})
public class PlatformController {
    private final PlatformRepository repository;
    private final ObjectMapper mapper;
    private final ObjectProvider<TestOidcConfiguration.OidcDependencyProbe> identityProbe;
    private final ObjectProvider<PhotoConfiguration.PhotoDependencyProbe> photoProbe;
    private final ObjectProvider<AssetImportConfiguration.ImportDependencyProbe> importProbe;
    private final ObjectProvider<ProjectIdentityService> projectIdentity;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private NetworkMonitoringService networkMonitoring;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private FirewallResourceService firewallResources;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private AssetExportPublicationSupport exportPublication;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ExportInboxSupport exportInbox;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.mop.domain.configuration.RuntimeConfigurationRepository runtimeConfigurations;
    private final byte[] cursorSecret=new byte[32];
    private static final Set<String> TYPES=Set.of("router","switch","firewall","ap","wireless_controller","other");
    private record Access(FixtureUser user,Tenant tenant,Membership membership,List<String> capabilities){}
    public PlatformController(PlatformRepository repository,ObjectMapper mapper,ObjectProvider<TestOidcConfiguration.OidcDependencyProbe> identityProbe,ObjectProvider<PhotoConfiguration.PhotoDependencyProbe> photoProbe,ObjectProvider<AssetImportConfiguration.ImportDependencyProbe> importProbe){this(repository,mapper,identityProbe,photoProbe,importProbe,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public PlatformController(PlatformRepository repository,ObjectMapper mapper,ObjectProvider<TestOidcConfiguration.OidcDependencyProbe> identityProbe,ObjectProvider<PhotoConfiguration.PhotoDependencyProbe> photoProbe,ObjectProvider<AssetImportConfiguration.ImportDependencyProbe> importProbe,ObjectProvider<ProjectIdentityService> projectIdentity){this.repository=repository;this.mapper=mapper;this.identityProbe=identityProbe;this.photoProbe=photoProbe;this.importProbe=importProbe;this.projectIdentity=projectIdentity;new SecureRandom().nextBytes(cursorSecret);}
    @GetMapping("/health/live") Object live(HttpServletRequest request){return success(map("status","UP","mode",repository.metadata().mode(),"source",repository.metadata().source()),request);}
    @GetMapping("/health/ready") Object ready(HttpServletRequest request){
        if(runtimeConfigurations!=null&&!runtimeConfigurations.readinessProblems().isEmpty())throw new DomainException(503,"UPSTREAM_UNAVAILABLE","运行配置发布结构未就绪",List.of(map("component","runtime_configuration","reasons",runtimeConfigurations.readinessProblems())));
        Map<String,Object> publicationStatus=exportPublication==null?map("enabled",false,"ready",false,"configured",false,"provider",null,"reasons",List.of("EXPORT_PUBLICATION_DISABLED")):exportPublication.status();
        if(Boolean.TRUE.equals(publicationStatus.get("enabled"))&&!Boolean.TRUE.equals(publicationStatus.get("ready")))throw new DomainException(503,"UPSTREAM_UNAVAILABLE","导出发布依赖未就绪",List.of(map("component","export_publication","status",publicationStatus)));
        Map<String,Object> inboxStatus=exportInbox==null?ExportInboxSupport.disabled().status():exportInbox.status();
        if(Boolean.TRUE.equals(inboxStatus.get("enabled"))&&!Boolean.TRUE.equals(inboxStatus.get("ready")))throw new DomainException(503,"UPSTREAM_UNAVAILABLE","导出消息未就绪",List.of(map("component","export_inbox","status",inboxStatus)));
        if(networkMonitoring!=null&&networkMonitoring.enabled()){
            var networkStatus=networkMonitoring.readiness();
            if(!Boolean.TRUE.equals(networkStatus.get("ready")))throw new DomainException(503,"UPSTREAM_UNAVAILABLE",
                "已启用的基础网络采集来源或新鲜样本未就绪",List.of(map("component","network_monitoring","readiness",networkStatus)));
        }
        if(firewallResources!=null&&firewallResources.enabled()){
            var status=firewallResources.readiness();if(!Boolean.TRUE.equals(status.get("ready")))throw new DomainException(503,"UPSTREAM_UNAVAILABLE","防火墙资源采集来源或新鲜样本未就绪",List.of(map("component","af_resources","readiness",status)));
        }
        if(!repository.dependencyReady())throw new DomainException(503,"UPSTREAM_UNAVAILABLE","测试环境 PostgreSQL 不可用，未就绪");
        ProjectIdentityService localIdentity=projectIdentity==null?null:projectIdentity.getIfAvailable();
        if(repository.metadata().mode().equals("remote_test")){
            if(localIdentity!=null&&!localIdentity.ready())throw new DomainException(503,"IDENTITY_UNAVAILABLE","项目账号与权限存储未就绪");
            if((localIdentity==null||localIdentity.oidcEnabled())&&(identityProbe.getIfAvailable()==null||!identityProbe.getIfAvailable().ready()))throw new DomainException(503,"UPSTREAM_UNAVAILABLE","已启用的统一身份源不可用，未就绪");
        }
        PhotoConfiguration.PhotoReadiness photos=new PhotoConfiguration.PhotoReadiness(false,false,false,false,List.of("PHOTO_NOT_CONFIGURED"));
        if(repository.metadata().mode().equals("remote_test")){
            PhotoConfiguration.PhotoDependencyProbe probe=photoProbe.getIfAvailable();
            if(probe==null)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","照片依赖探测未注册，未就绪",List.of(map("component","photos","reasons",List.of("PHOTO_PROBE_MISSING"))));
            photos=probe.status();
            if(photos.enabled()&&!photos.ready())throw new DomainException(503,"UPSTREAM_UNAVAILABLE","测试环境照片能力未就绪",List.of(map("component","photos","photos",photos.dto())));
        }
        AssetImportConfiguration.ImportReadiness imports=new AssetImportConfiguration.ImportReadiness(false,false,false,false,List.of("ASSET_IMPORT_NOT_CONFIGURED"));
        if(repository.metadata().mode().equals("remote_test")){
            AssetImportConfiguration.ImportDependencyProbe probe=importProbe.getIfAvailable();
            if(probe==null)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","导入源依赖探测未注册，未就绪",List.of(map("component","asset_imports","reasons",List.of("ASSET_IMPORT_PROBE_MISSING"))));
            imports=probe.status();
            if(imports.enabled()&&!imports.ready())throw new DomainException(503,"UPSTREAM_UNAVAILABLE","测试环境导入源能力未就绪",List.of(map("component","asset_imports","asset_imports",imports.dto())));
        }
        boolean dependencyDraftReady=false;
        if(repository.metadata().mode().equals("remote_test")){
            try{List<String> reasons=repository.assetDependencyProblems();dependencyDraftReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){dependencyDraftReady=false;}
            if(!dependencyDraftReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","测试环境资产依赖草稿schema或权限未就绪",List.of(map("component","asset_dependencies","reasons",List.of("ASSET_DEPENDENCY_SCHEMA_OR_ACCESS_UNAVAILABLE"))));
        }
        boolean worklogsReady=false;
        if(repository.metadata().mode().equals("remote_test")){
            try{List<String> reasons=repository.worklogProblems();worklogsReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){worklogsReady=false;}
            if(!worklogsReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","测试环境工时schema或权限未就绪",List.of(map("component","worklogs","reasons",List.of("WORKLOG_SCHEMA_OR_ACCESS_UNAVAILABLE"))));
        }
        boolean assetMetadataReady=false;
        if(repository.metadata().mode().equals("remote_test")){
            try{List<String> reasons=repository.assetMetadataProblems();assetMetadataReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){assetMetadataReady=false;}
            if(!assetMetadataReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","测试环境手工草稿资料schema或权限未就绪",List.of(map("component","asset_manual_metadata","reasons",List.of("ASSET_METADATA_SCHEMA_OR_ACCESS_UNAVAILABLE"))));
        }
        boolean assetReadReady=false;
        if(repository.metadata().mode().equals("remote_test")){
            try{List<String> reasons=repository.assetReadProblems();assetReadReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){assetReadReady=false;}
            if(!assetReadReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","测试环境资产Unicode查询依赖未就绪",List.of(map("component","asset_reads","reasons",List.of("ASSET_UNICODE_SEARCH_UNAVAILABLE"))));
        }
        boolean internalNotesReady=false;
        if(repository.metadata().mode().equals("remote_test")){
            try{List<String> reasons=repository.internalNoteProblems();internalNotesReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){internalNotesReady=false;}
            if(!internalNotesReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","测试环境内部备注schema或权限未就绪",List.of(map("component","internal_notes","reasons",List.of("INTERNAL_NOTE_SCHEMA_OR_ACCESS_UNAVAILABLE"))));
        }
        boolean ticketCommentsReady=false;
        if(repository.metadata().mode().equals("remote_test")){
            try{List<String> reasons=repository.ticketCommentProblems();ticketCommentsReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){ticketCommentsReady=false;}
            if(!ticketCommentsReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","测试环境客户评论schema或权限未就绪",List.of(map("component","ticket_comments","reasons",List.of("TICKET_COMMENT_SCHEMA_OR_ACCESS_UNAVAILABLE"))));
        }
        boolean workingCalendarsReady=false;
        if(repository.metadata().mode().equals("remote_test")){
            try{List<String> reasons=repository.workingCalendarProblems();workingCalendarsReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){workingCalendarsReady=false;}
            if(!workingCalendarsReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","工作日历草稿结构或权限未就绪",List.of(map("component","working_calendars","reasons",List.of("WORKING_CALENDAR_SCHEMA_OR_ACCESS_UNAVAILABLE"))));
        }
        boolean manualIncidentsReady=false;
        if(repository.metadata().mode().equals("remote_test")){
            try{List<String> reasons=repository.manualIncidentProblems();manualIncidentsReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){manualIncidentsReady=false;}
            if(!manualIncidentsReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","手工登记结构或权限未就绪",List.of(map("component","manual_incidents","reasons",List.of("MANUAL_INCIDENT_SCHEMA_OR_ACCESS_UNAVAILABLE"))));
        }
        boolean manualRecoveryReady=false;
        if(repository.metadata().mode().equals("remote_test")){
            try{List<String> reasons=repository.manualRecoveryProblems();manualRecoveryReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){manualRecoveryReady=false;}
            if(!manualRecoveryReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","人工恢复确认结构或权限未就绪",List.of(map("component","manual_recovery","reasons",List.of("MANUAL_RECOVERY_SCHEMA_OR_ACCESS_UNAVAILABLE"))));
        }
        boolean contactsReady=false;
        if("remote_test".equals(repository.metadata().mode())){
            try{List<String> reasons=repository.customerContactProblems();contactsReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){contactsReady=false;}
            if(!contactsReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","联系人结构或权限未就绪",List.of(map("component","customer_contacts","reasons",List.of("CUSTOMER_CONTACT_SCHEMA_ACCESS_NOT_READY"))));
        }
        boolean inspectionTemplatesReady=false;
        if("remote_test".equals(repository.metadata().mode())){
            try{List<String> reasons=repository.inspectionTemplateProblems();inspectionTemplatesReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){inspectionTemplatesReady=false;}
            if(!inspectionTemplatesReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","巡检模板结构或权限未就绪",List.of(map("component","inspection_templates","reasons",List.of("INSPECTION_TEMPLATE_STRUCTURE_UNAVAILABLE"))));
        }
        boolean inspectionWorksheetsReady=false;
        if("remote_test".equals(repository.metadata().mode())){
            try{List<String> reasons=repository.inspectionWorksheetProblems();inspectionWorksheetsReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){inspectionWorksheetsReady=false;}
            if(!inspectionWorksheetsReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","巡检执行单结构或权限未就绪",List.of(map("component","inspection_worksheets","reasons",List.of("INSPECTION_WORKSHEET_STRUCTURE_UNAVAILABLE"))));
        }
        boolean backupPlansReady=false;
        if("remote_test".equals(repository.metadata().mode())){
            try{List<String> reasons=repository.backupPlanDraftProblems();backupPlansReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){backupPlansReady=false;}
            if(!backupPlansReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","备份计划草稿结构或权限未就绪",List.of(map("component","backup_plan_drafts","reasons",List.of("BACKUP_PLAN_DRAFT_STRUCTURE_UNAVAILABLE"))));
        }
        boolean ownPreferencesReady=false;
        if("remote_test".equals(repository.metadata().mode())){
            try{List<String> reasons=repository.ownNotificationPreferenceProblems();ownPreferencesReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){ownPreferencesReady=false;}
            if(!ownPreferencesReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","本人通知偏好草稿结构或权限未就绪",List.of(map("component","own_notification_preferences","reasons",List.of("NOTIFICATION_PREFERENCE_DRAFT_STRUCTURE_UNAVAILABLE"))));
        }
        boolean assetExportsReady=false;
        if(repository.metadata().mode().equals("remote_test")){
            try{List<String> reasons=repository.assetExportProblems();assetExportsReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){assetExportsReady=false;}
            if(!assetExportsReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","导出准备结构或权限未就绪",List.of(map("component","asset_exports","reasons",List.of("ASSET_EXPORT_STRUCTURE_OR_GUARDS_UNAVAILABLE"))));
        }
        boolean changeNoticesReady=false;
        if("remote_test".equals(repository.metadata().mode())){
            try{List<String> reasons=repository.changeNoticeDraftProblems();changeNoticesReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){changeNoticesReady=false;}
            if(!changeNoticesReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","变更报备草稿结构或权限未就绪",List.of(map("component","change_notice_drafts","reasons",List.of("CHANGE_NOTICE_DRAFT_STRUCTURE_UNAVAILABLE"))));
        }
        boolean inspectionSourceCollectionsReady=false;
        if("remote_test".equals(repository.metadata().mode())){
            try{List<String> reasons=repository.inspectionSourceCollectionProblems();inspectionSourceCollectionsReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){inspectionSourceCollectionsReady=false;}
            if(!inspectionSourceCollectionsReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","巡检来源集合结构或权限未就绪",List.of(map("component","inspection_source_collections","reasons",List.of("INSPECTION_SOURCE_COLLECTION_STRUCTURE_UNAVAILABLE"))));
        }
        boolean contactImportsReady=false;
        if("remote_test".equals(repository.metadata().mode())){
            try{List<String> reasons=repository.contactImportProblems();contactImportsReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){contactImportsReady=false;}
            if(!contactImportsReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","联系人导入结构或权限未就绪",List.of(map("component","contact_imports","reasons",List.of("CONTACT_IMPORT_SCHEMA_ACCESS_NOT_READY"))));
        }
        boolean capacityScenariosReady=false;
        if("remote_test".equals(repository.metadata().mode())){
            try{List<String> reasons=repository.capacityScenarioProblems();capacityScenariosReady=reasons!=null&&reasons.isEmpty();}catch(RuntimeException unavailable){capacityScenariosReady=false;}
            if(!capacityScenariosReady)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","容量方案结构或权限未就绪",List.of(map("component","capacity_scenarios","reasons",List.of("CAPACITY_SCENARIO_SCHEMA_OR_ACCESS_UNAVAILABLE"))));
        }
        return success(map("status","READY_REMOTE_TEST","ready",true,"mode",repository.metadata().mode(),"source",repository.metadata().source(),"durable",repository.metadata().durable(),"production_ready",false,"runtime_configurations",map("schema_access_ready",runtimeConfigurations!=null,"scope","CURRENT_TENANT","customer_confirmation_status","UNVERIFIED"),"export_publication",publicationStatus,"export_inbox",inboxStatus,"own_notification_preferences",map("schema_access_ready",ownPreferencesReady,"configuration_status","CONFIG_PENDING","effective",false,"receiver_binding_status","NOT_VERIFIED","notification_dispatched",false),"asset_exports",map("schema_access_ready",assetExportsReady,"mode","PREPARE_ONLY_NO_PUBLICATION","worker_liveness",null,"publication_status","NOT_IMPLEMENTED","download_allowed",false),"change_notice_drafts",map("schema_access_ready",changeNoticesReady,"approval_status",null,"automatic_p3_decision",null,"approval_workflow_status","CONFIG_PENDING","matching_policy_status","CONFIG_PENDING","effective",false,"device_execution_allowed",false,"alert_suppression_allowed",false),"backup_plan_drafts",map("schema_access_ready",backupPlansReady,"effective",false,"device_execution_allowed",false,"credential_verification","NOT_VERIFIED","scheduler_running",null),"inspection_source_collections",map("schema_access_ready",inspectionSourceCollectionsReady,"verification_status","SELF_REPORTED_UNVERIFIED","generation_status","CONFIG_PENDING","formal_report_generatable",false,"report_published",false),"inspection_worksheets",map("schema_access_ready",inspectionWorksheetsReady,"verification_status","SELF_REPORTED_UNVERIFIED","evidence_binding_status","NOT_IMPLEMENTED","publication_status","NOT_IMPLEMENTED","report_published",false,"active_scan_performed",false),"inspection_templates",map("schema_access_ready",inspectionTemplatesReady,"approval_status","UNVERIFIED","publication_status","NOT_IMPLEMENTED","report_published",false,"active_scan_performed",false),"contact_imports",map("schema_access_ready",contactImportsReady,"worker_liveness",null,"notification_dispatched",false,"identity_binding_created",false),"customer_contacts",map("schema_access_ready",contactsReady,"notification_dispatched",false,"channel_verification","NOT_VERIFIED","identity_binding_created",false),"capacity_scenarios",map("schema_access_ready",capacityScenariosReady,"effective",false,"measurement_verified",false,"poc_executed",false),"manual_recovery",map("schema_access_ready",manualRecoveryReady,"source","HUMAN_CONFIRMATION","monitoring_recovery_applicable",false,"ticket_state_changed",false,"sla_changed",false),"manual_incidents",map("schema_access_ready",manualIncidentsReady,"source_state","MANUAL","registration_status","REGISTERED_ONLY","monitoring_recovery_applicable",false,"ticket_automatically_created",false),"working_calendars",map("schema_access_ready",workingCalendarsReady,"effective",false,"customer_confirmation","UNVERIFIED"),"photos",photos.dto(),"asset_imports",imports.dto(),"asset_reads",map("unicode_search_ready",assetReadReady,"pagination","BOUNDED_POSTGRESQL_KEYSET"),"ticket_comments",map("schema_access_ready",ticketCommentsReady,"visibility","REPORTER_AND_INTERNAL","internal_identity_exposed",false,"itsm_applied",false),"internal_notes",map("schema_access_ready",internalNotesReady,"visibility","INTERNAL","ticket_state_changed",false,"sla_changed",false),"asset_manual_metadata",map("schema_access_ready",assetMetadataReady,"scope","INTERNAL_MANUAL_DRAFT","monitoring_applied",false,"cmdb_applied",false),"worklogs",map("ready",worklogsReady,"schema_access_ready",worklogsReady,"financial_status","CONFIG_PENDING"),"asset_dependencies",map("ready",dependencyDraftReady,"schema_access_ready",dependencyDraftReady,"configuration_status","CONFIG_PENDING","monitoring_applied",false,"effective_rules",null)),request);
    }
    @GetMapping("/assets") Object assets(@RequestParam Map<String,String> query,HttpServletRequest request){
        Access access=access(request,"assets:read");Set<String> allowed=Set.of("cursor","page_size","type","site_id","status","q");
        if(!allowed.containsAll(query.keySet())||query.values().stream().anyMatch(v->v.length()>2048)||request.getParameterMap().values().stream().anyMatch(v->v.length!=1))throw validation("查询参数不在白名单或重复");
        int size=15;try{if(query.containsKey("page_size"))size=Integer.parseInt(query.get("page_size"));}catch(NumberFormatException e){throw validation("page_size 必须为整数");}
        if(!Set.of(10,15,25,50,100).contains(size))throw validation("测试阶段技术单页上限为100，选项10/15/25/50/100；正式上限待确认");
        if(query.containsKey("type")&&!TYPES.contains(query.get("type")))throw validation("type 不在枚举中");
        if(query.containsKey("status")&&!Set.of("unknown","ok","problem","maintenance","disabled").contains(query.get("status")))throw validation("status 不在枚举中");
        if(query.getOrDefault("q","").length()>100||query.getOrDefault("site_id","").length()>64)throw validation("筛选文字过长");
        SortedMap<String,String> filters=new TreeMap<>(query);filters.remove("cursor");filters.remove("page_size");String filterHash=hash(mapper.valueToTree(filters));
        JsonNode cursor=query.containsKey("cursor")?readCursor(query.get("cursor"),access,filterHash):null;
        List<Asset> matches=repository.authorizedAssets(access.tenant.id(),access.user.id(),access.membership.permissionVersion(),
            new AssetReadQuery(query.get("type"),query.get("status"),query.get("site_id"),query.get("q"),size+1,cursor==null?null:Instant.parse(cursor.path("created_at").asText()),cursor==null?null:cursor.path("id").asText()));
        boolean more=matches.size()>size;List<Asset> items=matches.subList(0,Math.min(size,matches.size()));
        String next=more?cursor(access,filterHash,items.get(items.size()-1)):null;
        return success(map("items",items,"next_cursor",next,"has_more",more,"source",repository.metadata().source(),"page_size_limit",100,"page_size_limit_kind","test_technical_limit"),request);
    }
    @GetMapping("/assets/{id}") Object asset(@PathVariable String id,HttpServletRequest request){Access access=access(request,"assets:read");return success(repository.authorizedAsset(access.tenant.id(),access.user.id(),access.membership.permissionVersion(),id).orElseThrow(()->notFound()),request);}
    @PostMapping("/assets") Object createAsset(@RequestBody JsonNode body,HttpServletRequest request){
        Access access=access(request,"assets:write");fields(body,Set.of("name","type","management_ip","site_id"));
        String name=text(body,"name",100),type=text(body,"type",32),ip=text(body,"management_ip",45),site=text(body,"site_id",64);
        if(!TYPES.contains(type))throw validation("type 不在设备枚举中");if(!validIp(ip))throw validation("management_ip 必须为合法 IPv4/IPv6 字面量");
        Object data=write(access,body,request,()->{Asset result=repository.insert(access.tenant.id(),access.user.id(),name,type,ip,site);repository.audit(access.user.id(),access.tenant.id(),"asset.create_draft","asset",result.id(),"SUCCEEDED","DRAFT_ONLY",trace(request));return result;});
        return success(data,request);
    }
    @PostMapping("/assets/{id}/onboarding") Object onboard(@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest request){
        Access access=access(request,"assets:write");fields(body,Set.of("expected_version"));long version=version(body);
        Asset asset=repository.find(access.tenant.id(),id).orElseThrow(()->notFound());
        return success(write(access,body,request,()->{if(asset.version()!=version)throw conflict();throw pending(List.of("Q02","Q03","Q06"),"数据库、组件和监控规则待确认，未受理接入任务");}),request);
    }
    @GetMapping("/configurations") Object configurations(@RequestParam Map<String,String> query,HttpServletRequest request){
        access(request,"configurations:read");if(!Set.of("decision_status").containsAll(query.keySet())||request.getParameterMap().values().stream().anyMatch(v->v.length!=1))throw validation("配置查询仅允许decision_status");
        if(query.containsKey("decision_status")&&!Set.of("pending","contract_fixed").contains(query.get("decision_status")))throw validation("decision_status 不在登记枚举中");
        List<Map<String,Object>> items=repository.configurations().stream().filter(item->!query.containsKey("decision_status")||Objects.equals(item.get("decision_status"),query.get("decision_status"))).toList();
        return success(map("items",items,"source",repository.metadata().source(),"registry_status","design_registry_not_runtime_config","runtime_activation",false),request);
    }
    @PostMapping("/configurations/{key}/draft") Object configDraft(@PathVariable String key,@RequestBody JsonNode body,HttpServletRequest request){
        Access access=access(request,"configurations:write");fields(body,Set.of("proposed_value","expected_version"));long expected=version(body);
        Map<String,Object> entry=repository.configuration(key);JsonNode proposed=body.get("proposed_value");if(proposed==null||proposed.isNull())throw validation("proposed_value 必须非null且符合登记类型");
        ConfigurationValueValidator.validate(entry,proposed);
        Object data=write(access,body,request,()->{Map<String,Object> result=repository.draftConfiguration(key,mapper.convertValue(proposed,Object.class),expected);repository.audit(access.user.id(),access.tenant.id(),"configuration.save_draft","configuration",key,"SUCCEEDED","DRAFT_NOT_EFFECTIVE",trace(request));return result;});return success(data,request);
    }
    @PostMapping("/configurations/{key}/publish") Object publish(@PathVariable String key,@RequestBody JsonNode body,HttpServletRequest request){
        Access access=access(request,"configurations:write");fields(body,Set.of("expected_version"));long expected=version(body);Map<String,Object> entry=repository.configuration(key);
        return success(write(access,body,request,()->{if(((Number)entry.get("version")).longValue()!=expected)throw conflict();
            List<?> questions=(List<?>)entry.getOrDefault("question_ids",List.of());
            throw pending(questions.isEmpty()?List.of("Q02","Q17"):questions,"当前仅有设计登记与测试环境草稿，未配置正式审批及生效存储，未发布");}),request);
    }
    @GetMapping("/audit") Object audit(HttpServletRequest request){Access access=access(request,"audit:read");return success(map("items",repository.audits(access.tenant.id()),"source",repository.metadata().source(),"durable",repository.metadata().durable()),request);}

    private Access access(HttpServletRequest request,String capability){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw new DomainException(401,"SESSION_EXPIRED","会话不存在或已过期");
        Object tenantValue=request.getSession(false).getAttribute("mop.tenant_id");if(!(tenantValue instanceof String tenant)||tenant.isBlank())throw new DomainException(401,"SESSION_EXPIRED","当前租户会话无效");
        String actor=request.getUserPrincipal().getName();
        Membership membership=repository.membership(actor,tenant).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","成员关系已失效，请重新登录"));
        if(!membership.active())throw new DomainException(401,"SESSION_EXPIRED","成员关系已失效，请重新登录");
        List<String> capabilities=membership.capabilities();if(!capabilities.contains(capability))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无此动作权限");
        return new Access(repository.user(actor).orElseThrow(()->notFound()),repository.tenant(tenant),membership,capabilities);
    }
    private Object write(Access access,JsonNode body,HttpServletRequest request,Supplier<Object> command){
        return repository.idempotent(access.user.id(),access.tenant.id(),route(request),requestKey(request),hash(body),access.membership.permissionVersion(),()->{
            // Recheck under the same monitor as the write; concurrent revocation cannot race a commit.
            repository.membership(access.user.id(),access.tenant.id()).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","成员关系已失效"));return command.get();
        });
    }
    private String requestKey(HttpServletRequest request){String key=request.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw validation("Idempotency-Key 为必填，长度1至128且仅包含字母数字及._:-");return key;}
    private static void fields(JsonNode body,Set<String> allowed){if(!body.isObject())throw validation("请求体必须为JSON对象");body.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw validation("未知或禁止字段: "+k);});}
    private static String text(JsonNode body,String key,int max){JsonNode value=body.get(key);if(value==null||!value.isTextual())throw validation(key+" 必须为字符串");String result=value.asText().strip();if(result.isEmpty()||result.length()>max||result.codePoints().anyMatch(Character::isISOControl))throw validation(key+" 为空、过长或含控制字符");return result;}
    private static long version(JsonNode body){JsonNode value=body.get("expected_version");if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<1)throw validation("expected_version 必须为正整数");return value.asLong();}
    private static boolean validIp(String ip){
        if(ip.contains(":")){if(!ip.matches("[0-9a-fA-F:.]+"))return false;try{InetAddress.getByName(ip);return true;}catch(Exception e){return false;}}
        if(!ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))return false;for(String octet:ip.split("\\.")){if(Integer.parseInt(octet)>255||octet.length()>1&&octet.startsWith("0"))return false;}return true;
    }
    private String hash(JsonNode value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical(value))));}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode canonical(JsonNode value){if(value.isObject()){ObjectNode node=mapper.createObjectNode();TreeSet<String> fields=new TreeSet<>();value.fieldNames().forEachRemaining(fields::add);fields.forEach(k->node.set(k,canonical(value.get(k))));return node;}if(value.isArray()){var array=mapper.createArrayNode();value.forEach(v->array.add(canonical(v)));return array;}return value;}
    private String cursor(Access access,String filter,Asset last){try{String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(map("tenant",access.tenant.id(),"actor",access.user.id(),"permission_version",access.membership.permissionVersion(),"filter",filter,"created_at",last.createdAt().toString(),"id",last.id())));return payload+"."+signature(payload);}catch(Exception e){throw new IllegalStateException(e);}}
    private String signature(String payload){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(cursorSecret,"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode readCursor(String token,Access access,String filter){try{String[] parts=token.split("\\.");if(parts.length!=2||!MessageDigest.isEqual(parts[1].getBytes(StandardCharsets.UTF_8),signature(parts[0]).getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException();JsonNode cursor=mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));if(!cursor.path("tenant").asText().equals(access.tenant.id())||!cursor.path("actor").asText().equals(access.user.id())||cursor.path("permission_version").asLong()!=access.membership.permissionVersion()||!cursor.path("filter").asText().equals(filter))throw new IllegalArgumentException();Instant.parse(cursor.path("created_at").asText());return cursor;}catch(Exception e){throw new DomainException(400,"CURSOR_INVALID","游标无效、过期或不属于当前授权筛选范围");}}
    private static DomainException validation(String message){return new DomainException(400,"VALIDATION_ERROR",message);}
    private static DomainException notFound(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException conflict(){return new DomainException(409,"VERSION_CONFLICT","对象版本已变化，请刷新");}
    private static DomainException pending(List<?> questions,String message){return new DomainException(422,"CONFIG_PENDING",message,List.of(map("question_ids",questions)));}
}
