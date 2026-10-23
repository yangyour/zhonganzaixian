/** D09 append-only report drafts; request configuration never implies runtime/customer approval. */
export function addInspectionQuarterlyReportContract(contract) {
  const object = properties => ({type:'object',properties,required:Object.keys(properties),additionalProperties:false});
  const ref = name => ({$ref:`#/components/schemas/${name}`});
  const nullable = schema => ({anyOf:[schema,{type:'null'}]});
  const text = {type:'string'}, uuid={type:'string',format:'uuid'}, digest={type:'string',pattern:'^[0-9a-f]{64}$'}, time={type:'string',format:'date-time'};
  const array = (items,maxItems=1000) => ({type:'array',items,maxItems});
  const number={type:'integer',minimum:0}, version={type:'integer',minimum:1,maximum:9007199254740990};
  const configText=nullable({type:'string',minLength:1,maxLength:200});
  const duplicate=nullable({enum:['REQUIRE_SINGLE_SOURCE','LATEST_REPORTED_PERFORMED_AT']}), archived=nullable({enum:['INCLUDE','EXCLUDE']});
  const config=object({revision:configText,time_zone:nullable({type:'string',minLength:1,maxLength:100}),duplicate_rule:duplicate,archived_rule:archived,metric_version:configText,report_template_version:configText});
  const storedConfig=object({revision:configText,timeZone:config.properties.time_zone,duplicateRule:duplicate,archivedRule:archived,metricVersion:configText,reportTemplateVersion:configText});
  const period={year:{type:'integer',minimum:1,maximum:9998},quarter:{type:'integer',minimum:1,maximum:4}};
  const item=object({key:text,section:text,label:text,instruction:nullable(text)});
  const observation=object({result:{enum:['NORMAL','ATTENTION','REQUIRES_ACTION']},note:nullable(text),evidenceIds:array(uuid,10)});
  const sourceReference=object({worksheetId:uuid,version,snapshotSha256:digest,contentSha256:digest});
  const worksheetSummary=object({total:number,answered:number,normal:number,attention:number,requiresAction:number,unanswered:number});
  const sourceDocument=object({format:{const:'MOP_INSPECTION_SOURCE_DOCUMENT_V1'},tenant_id:uuid,manifest_sha256:digest,sources:array(object({worksheet_id:uuid,version,created_by:uuid,created_at:time,recorded_by:uuid,recorded_at:time,action:{enum:['CREATE','UPDATE','ARCHIVE','RESTORE']},archived:{type:'boolean'},source_sha256:digest,content:object({asset_id:uuid,asset_name:text,reported_performed_at:nullable(time),content_sha256:digest,template:object({id:uuid,version,device_type:text,name:text,content_sha256:digest,items:array(item,100)}),observations:array(object({key:text,result:observation.properties.result,note:nullable(text),evidence_ids:array(uuid,10)}),100)})}))});
  const pending={enum:['TIME_ZONE_MISSING','DUPLICATE_RULE_MISSING','ARCHIVE_RULE_MISSING','METRIC_VERSION_MISSING','REPORT_TEMPLATE_VERSION_MISSING','CONFIG_REVISION_MISSING','SOURCE_SET_UNKNOWN','SOURCE_SET_INCOMPLETE','EMPTY_SELECTION','UNRESOLVED_ASSET_SELECTION','UNANSWERED_ITEMS','SELF_REPORTED_SOURCES_UNVERIFIED','TEMPLATE_APPROVAL_UNVERIFIED','EVIDENCE_REFERENCES_UNVERIFIED','PUBLICATION_PIPELINE_NOT_CONNECTED']};
  const flags={generation_status:{const:'DRAFT'},report_published:{const:false},report_publishable:{const:false},evidence_access_granted:{const:false},tenant_asset_population:{type:'null'}};
  const output=object({...flags,summary:object({capturedAssets:number,selectedAssets:number,unresolvedAssets:number,totalItems:number,answered:number,normal:number,attention:number,requiresAction:number,unanswered:number}),pending_reasons:array(pending,16),rows:array(object({asset_id:uuid,status:{enum:['SELECTED','NO_SOURCE_IN_PERIOD','CONFIG_PENDING','SOURCE_TIME_UNKNOWN','ARCHIVE_POLICY_PENDING','MULTIPLE_SOURCES','LATEST_TIME_TIED']},asset_name:nullable(text),summary:nullable(worksheetSummary),items:array(object({item,observation:nullable(observation)}),100),selected:nullable(sourceReference),candidates:array(sourceReference)})),findings:array(object({assetId:uuid,source:sourceReference,item,observation}),100000)});
  const frozen=object({format:{const:'MOP_INSPECTION_QUARTERLY_DOCUMENT_V1'},tenant_id:uuid,source_collection_id:uuid,source_document_sha256:digest,source_manifest_sha256:digest,report_sha256:digest,configuration_source:{const:'REQUEST_DRAFT_NOT_APPROVED'},configuration:storedConfig,...period,completeness:{const:'COMPLETE_EXPLICIT_SELECTION'},source_document:sourceDocument,output});
  const summary=object({id:uuid,tenant_id:uuid,created_by:uuid,recorded_at:time,version:{const:1},label:{type:'string',minLength:1,maxLength:200},source_collection_id:uuid,source_document_sha256:digest,source_manifest_sha256:digest,...period,report_sha256:digest,document_format:{const:'MOP_INSPECTION_QUARTERLY_DOCUMENT_V1'},document_sha256:digest,projection:{const:'METADATA_ONLY'},configuration_source:{const:'REQUEST_DRAFT_NOT_APPROVED'},scope:{const:'CURRENT_TENANT_PADMIN'},source:{const:'postgresql'},...flags});
  const envelope=data=>object({data,trace_id:text});
  Object.assign(contract.components.schemas,{
    InspectionQuarterlyReportConfiguration:config,
    InspectionQuarterlyReportCreate:object({expected_version:{const:0},label:summary.properties.label,source_collection_id:uuid,expected_source_document_sha256:digest,expected_source_manifest_sha256:digest,...period,configuration:config}),
    InspectionQuarterlyReportSummary:summary,
    InspectionQuarterlyReportCapture:object({expected_version:{const:0},label:summary.properties.label,
      ...period,configuration:config,sources:contract.components.schemas.InspectionSourceCollectionCreate.properties.sources}),
    InspectionQuarterlyReportCaptureSummary:object({...summary.properties,capture_mode:{const:'ATOMIC_EXPLICIT_WORKSHEET_REVISIONS'}}),
    InspectionQuarterlyReportCaptureResult:envelope(ref('InspectionQuarterlyReportCaptureSummary')),
    InspectionQuarterlyReportFrozenDocument:frozen,
    InspectionQuarterlyReportDetail:object({...summary.properties,projection:{const:'FROZEN_INPUT_OUTPUT'},document_integrity:{const:'VERIFIED_SERVER_SIDE'},frozen_document:ref('InspectionQuarterlyReportFrozenDocument')}),
    InspectionQuarterlyReportSummaryResult:envelope(ref('InspectionQuarterlyReportSummary')),
    InspectionQuarterlyReportDetailResult:envelope(ref('InspectionQuarterlyReportDetail')),
    InspectionQuarterlyReportPageResult:envelope(object({tenant_id:uuid,items:array(ref('InspectionQuarterlyReportSummary'),20),has_more:{type:'boolean'},next_before_recorded_at:nullable(time),next_before_id:nullable(uuid),page_size_limit:{const:20},scope:{const:'CURRENT_TENANT_PADMIN'},source:{const:'postgresql'}}))
  });
  const errors=Object.fromEntries([400,401,403,404,409,413,415,503].map(code=>[code,{description:'Input, authorization, exact-source hash conflict or unavailable storage; never silently replaces source.',content:{'application/json':{schema:ref('Error')}}}]));
  const op=(operationId,result,extra={})=>({operationId,summary:'Immutable quarterly DRAFT bound to exact frozen source and explicit unapproved configuration','x-mop-roles':['padmin'],'x-mop-current-membership-recheck':true,'x-mop-capability':'inspection_quarterly_reports:read',responses:{200:{description:'Draft snapshot only, not publication or customer approval',content:{'application/json':{schema:ref(result)}}},...errors},...extra});
  contract.paths['/inspection-quarterly-reports']={
    get:op('listInspectionQuarterlyReports','InspectionQuarterlyReportPageResult',{parameters:[{name:'page_size',in:'query',schema:{type:'integer',enum:[10,15,20],default:15}},{name:'before_recorded_at',in:'query',schema:time},{name:'before_id',in:'query',schema:uuid}],'x-mop-document-projection':'METADATA_ONLY'}),
    post:op('createInspectionQuarterlyReport','InspectionQuarterlyReportSummaryResult',{'x-mop-capability':'inspection_quarterly_reports:write','x-mop-additional-capabilities':['inspection_source_collections:read'],'x-mop-idempotent-replay-reauthorization':true,'x-mop-max-body-bytes':16384,parameters:[{name:'Idempotency-Key',in:'header',required:true,schema:{type:'string',pattern:'^[A-Za-z0-9._:-]{1,128}$'}},{name:'X-CSRF-Token',in:'header',required:true,schema:text}],requestBody:{required:true,content:{'application/json':{schema:ref('InspectionQuarterlyReportCreate')}}}})
  };
  contract.paths['/inspection-quarterly-reports/{id}']={get:op('getInspectionQuarterlyReport','InspectionQuarterlyReportDetailResult',{parameters:[{name:'id',in:'path',required:true,schema:uuid}],'x-mop-document-projection':'FROZEN_INPUT_OUTPUT'})};
  contract.paths['/inspection-quarterly-reports/capture']={post:op('captureInspectionQuarterlyReport','InspectionQuarterlyReportCaptureResult',{
    summary:'Atomically capture explicit worksheet revisions, source collection and reproducible quarterly DRAFT',
    'x-mop-capability':'inspection_quarterly_reports:write',
    'x-mop-additional-capabilities':['inspection_source_collections:read','inspection_source_collections:write','inspection_worksheets:write'],
    'x-mop-idempotent-replay-reauthorization':true,'x-mop-max-body-bytes':262144,
    'x-mop-atomic-writes':['source_collection','source_collection_items','quarterly_report','audit','idempotency_receipt'],
    parameters:contract.paths['/inspection-quarterly-reports'].post.parameters,
    requestBody:{required:true,content:{'application/json':{schema:ref('InspectionQuarterlyReportCapture')}}}
  })};
  const readiness=contract.components.schemas.ReadinessResult?.properties?.data;
  if(readiness){readiness.properties.inspection_quarterly_reports=object({schema_access_ready:{type:'boolean'},configuration_source:{const:'REQUEST_DRAFT_NOT_APPROVED'},generation_status:{const:'DRAFT'},report_published:{const:false},report_publishable:{const:false},evidence_access_granted:{const:false}});if(!readiness.required.includes('inspection_quarterly_reports'))readiness.required.push('inspection_quarterly_reports');}
}
