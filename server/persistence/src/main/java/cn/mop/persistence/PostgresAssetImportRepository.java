package cn.mop.persistence;

import cn.mop.domain.Capabilities;
import cn.mop.domain.DomainException;
import cn.mop.domain.assetimport.AssetImportPrechecker.Column;
import cn.mop.domain.assetimport.source.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Durable technical staging. No asset write, quota, business normalization or photo outbox. */
public final class PostgresAssetImportRepository implements AssetImportSourceRepository {
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final PostgresPlatformRepository platform;
    private static final String SOURCE_SELECT="SELECT s.* FROM public.mop_asset_import_source s WHERE s.tenant_id=?::uuid AND s.id=?::uuid AND s.created_by=?::uuid";
    public PostgresAssetImportRepository(DataSource dataSource,ObjectMapper mapper,PostgresPlatformRepository platform){
        this.dataSource=Objects.requireNonNull(dataSource);this.mapper=Objects.requireNonNull(mapper).copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);this.platform=Objects.requireNonNull(platform);
    }
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","资产导入暂不可用，未返回替代结果");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","导入源或操作不存在或不可见");}
    private static DomainException conflict(){return new DomainException(409,"VERSION_CONFLICT","导入源版本已变化，请重新读取");}
    private static DomainException state(){return new DomainException(422,"STATE_NOT_ALLOWED","导入源当前状态不允许此动作");}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","导入源请求或列映射无效");}
    private static String uuid(){return UUID.randomUUID().toString();}
    private static boolean uuid(String value){return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception failure){throw new IllegalStateException("SHA-256 unavailable");}}
    private static void validAccess(Access access){if(access==null)throw new DomainException(401,"SESSION_EXPIRED","会话或成员关系已失效");}
    /** Reads use their own RR connection; writes lock current membership on the idempotent connection. */
    private void authorize(Connection connection,Access access,boolean write,boolean lock)throws SQLException{
        validAccess(access);
        try(PreparedStatement statement=prepare(connection,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.user_id=?::uuid AND m.tenant_id=?::uuid AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""),access.actorId(),access.tenantId());ResultSet rows=statement.executeQuery()){
            if(!rows.next()||rows.getLong("permission_version")!=access.permissionVersion())throw new DomainException(401,"SESSION_EXPIRED","会话或权限已变化，请刷新会话");
            if(write&&!PostgresAuthorization.capabilities(connection,access.actorId(),access.tenantId()).contains("assets:write"))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色不能执行资产导入暂存动作");
        }
    }
    @FunctionalInterface private interface SqlWork<T>{T run(Connection connection)throws SQLException;}
    private <T>T command(Access access,SqlWork<T> work){
        Connection connection=platform.importCommandConnection();
        try{authorize(connection,access,true,true);return work.run(connection);}catch(SQLException failure){throw unavailable();}
    }
    @Override public Map<String,Object> acceptSource(Access access,String filename,ImportFormat format,byte[] original){
        // The caller must already be inside PlatformRepository.idempotent; never open a second command transaction.
        return command(access,connection->{
            if(format==null||filename==null||filename.isBlank()||filename.codePointCount(0,filename.length())>200||filename.contains("/")||filename.contains("\\")||filename.codePoints().anyMatch(Character::isISOControl)||original==null||original.length<1||original.length>ImportLimits.MAX_SOURCE_BYTES)throw invalid();
            byte[] content=original.clone();String source=uuid(),operation=uuid(),hash=sha(content),key="asset-import-sources/"+access.tenantId()+"/"+source;
            execute(connection,"INSERT INTO public.mop_asset_import_source(id,tenant_id,created_by,original_name,declared_format,size_bytes,sha256,object_key,permission_version,store_operation_id) VALUES (?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?::uuid)",source,access.tenantId(),access.actorId(),filename,format.name(),content.length,hash,key,access.permissionVersion(),operation);
            insertOperation(connection,access,source,operation,"STORE_RAW");
            execute(connection,"INSERT INTO public.mop_asset_import_job(id,tenant_id,source_id,operation_id,kind,content) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,'STORE_RAW',?)",uuid(),access.tenantId(),source,operation,content);
            audit(access,"asset.import.source.accept",source,"SUCCEEDED","DURABLE_STAGING_ONLY");return accepted(source,operation);
        });
    }
    @Override public Map<String,Object> requestParse(Access access,String sourceId,long expectedVersion,ParseSelection selection){
        return command(access,connection->{
            if(!uuid(sourceId))throw missing();if(expectedVersion<1||selection==null)throw invalid();
            Source source=sourceRow(connection,access,sourceId,true);if(source.version()!=expectedVersion)throw conflict();
            if(!"READY".equals(source.storage())||"PENDING".equals(source.parse()))throw state();if(selection.declaredFormat()!=source.format())throw invalid();
            String operation=uuid();insertOperation(connection,access,sourceId,operation,"PARSE");
            execute(connection,"INSERT INTO public.mop_asset_import_job(id,tenant_id,source_id,operation_id,kind,selection_json) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,'PARSE',?::jsonb)",uuid(),access.tenantId(),sourceId,operation,jsonText(selection));
            if(execute(connection,"UPDATE public.mop_asset_import_source SET parse_operation_id=?::uuid,parse_status='PENDING',latest_artifact_id=NULL,latest_mapping_id=NULL,version=version+1,updated_at=clock_timestamp() WHERE tenant_id=?::uuid AND id=?::uuid AND created_by=?::uuid AND version=?",operation,access.tenantId(),sourceId,access.actorId(),expectedVersion)!=1)throw conflict();
            audit(access,"asset.import.parse.request",sourceId,"SUCCEEDED","PARSE_SELECTION_RECORDED");return accepted(sourceId,operation);
        });
    }
    @Override public Map<String,Object> saveMapping(Access access,String sourceId,MappingRequest mapping){
        return command(access,connection->{
            if(!uuid(sourceId))throw missing();if(mapping==null)throw invalid();Source source=sourceRow(connection,access,sourceId,true);
            if(source.version()!=mapping.expectedVersion())throw conflict();if(!"PARSED".equals(source.parse())||source.artifact()==null)throw state();
            Map<String,Object> stored=artifact(connection,source);if(stored==null)throw unavailable();String parseHash=(String)stored.get("parse_hash");if(!parseHash.equals(mapping.sourceParseHash()))throw conflict();
            ParsedArtifact parsed=parsedArtifact(stored);try{mapping.validateAgainst(parsed);}catch(IllegalArgumentException failure){throw invalid();}
            List<Map<String,Object>> rawRows=mappedRows(parsed,mapping);String mappingId=uuid();
            Map<String,Object> binding=new LinkedHashMap<>();binding.put("tenant_id",source.tenant());binding.put("source_id",source.id());binding.put("source_sha256",source.sha());binding.put("source_parse_hash",parseHash);binding.put("schema_version",AssetImportSchema.VERSION);binding.put("target_source_columns",mapping.targetSourceColumns());binding.put("ignored_source_columns",new TreeSet<>(mapping.ignoredSourceColumns()));binding.put("mapped_raw_rows",rawRows);
            String preview=canonicalHash(mapper.valueToTree(binding));long mappingVersion=nextMappingVersion(connection,source);
            execute(connection,"INSERT INTO public.mop_asset_import_mapping(id,tenant_id,source_id,artifact_id,created_by,permission_version,version,source_parse_hash,preview_hash,schema_version,target_source_columns,ignored_source_columns,mapped_raw_rows) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb)",mappingId,source.tenant(),source.id(),source.artifact(),access.actorId(),access.permissionVersion(),mappingVersion,parseHash,preview,AssetImportSchema.VERSION,jsonText(mapping.targetSourceColumns()),jsonText(new TreeSet<>(mapping.ignoredSourceColumns())),jsonText(rawRows));
            if(execute(connection,"UPDATE public.mop_asset_import_source SET latest_mapping_id=?::uuid,version=version+1,updated_at=clock_timestamp() WHERE tenant_id=?::uuid AND id=?::uuid AND created_by=?::uuid AND version=?",mappingId,source.tenant(),source.id(),access.actorId(),source.version())!=1)throw conflict();
            audit(access,"asset.import.mapping.save",sourceId,"SUCCEEDED","PREVIEW_ONLY_CONFIG_PENDING");return sourceDto(connection,sourceRow(connection,access,sourceId,false));
        });
    }
    private void insertOperation(Connection connection,Access access,String source,String operation,String kind)throws SQLException{
        execute(connection,"INSERT INTO public.mop_asset_import_operation(id,tenant_id,source_id,created_by,permission_version,kind) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,?)",operation,access.tenantId(),source,access.actorId(),access.permissionVersion(),kind);
    }
    private static Map<String,Object> accepted(String source,String operation){return Map.of("source_id",source,"operation_id",operation,"status","PENDING");}
    private void audit(Access access,String action,String source,String result,String reason){platform.audit(access.actorId(),access.tenantId(),action,"asset_import_source",source,result,reason,access.traceId());}
    private <T>T read(Access access,SqlWork<T> work){
        try(Connection connection=dataSource.getConnection()){
            connection.setReadOnly(true);connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
            try{authorize(connection,access,false,false);T value=work.run(connection);connection.commit();return value;}
            catch(SQLException|RuntimeException|Error failure){try{connection.rollback();}catch(SQLException ignored){}throw failure;}
        }catch(SQLException failure){throw unavailable();}
    }
    @Override public Map<String,Object> source(Access access,String sourceId){if(!uuid(sourceId))throw missing();return read(access,c->sourceDto(c,sourceRow(c,access,sourceId,false)));}
    @Override public Map<String,Object> listSources(Access access,int limit,String cursor){
        if(limit<1||limit>50)throw invalid();if(cursor!=null&&!uuid(cursor))throw missing();
        return read(access,connection->{
            authorize(connection,access,true,false);
            if(cursor!=null)sourceRow(connection,access,cursor,false);
            String query="SELECT s.* FROM public.mop_asset_import_source s WHERE s.tenant_id=?::uuid AND s.created_by=?::uuid"+(cursor==null?"":" AND (s.created_at,s.id)<(SELECT a.created_at,a.id FROM public.mop_asset_import_source a WHERE a.tenant_id=?::uuid AND a.created_by=?::uuid AND a.id=?::uuid)")+" ORDER BY s.created_at DESC,s.id DESC LIMIT ?";
            List<Object> args=new ArrayList<>(List.of(access.tenantId(),access.actorId()));
            if(cursor!=null)args.addAll(List.of(access.tenantId(),access.actorId(),cursor));args.add(limit+1);
            var items=new ArrayList<Map<String,Object>>();String next=null;
            try(PreparedStatement statement=prepare(connection,query,args.toArray());ResultSet rows=statement.executeQuery()){
                while(rows.next()){
                    if(items.size()==limit){next=(String)items.get(items.size()-1).get("id");break;}
                    var item=new LinkedHashMap<String,Object>();
                    for(String field:List.of("id","original_name","declared_format","storage_status","parse_status"))item.put(field,rows.getString(field));
                    item.put("size_bytes",rows.getLong("size_bytes"));item.put("version",rows.getLong("version"));item.put("mapping_saved",rows.getString("latest_mapping_id")!=null);times(item,rows);items.add(item);
                }
            }
            var result=new LinkedHashMap<String,Object>();result.put("tenant_id",access.tenantId());result.put("created_by",access.actorId());result.put("scope","CREATOR_ONLY");result.put("items",items);result.put("next_cursor",next);result.put("can_import",false);return result;
        });
    }
    @Override public AssetNameSnapshot assetNameSnapshot(Access access,String sourceId){
        if(!uuid(sourceId))throw missing();
        return read(access,connection->{
            // Recheck the capability in the same snapshot as the owner-bound artifact and tenant assets.
            authorize(connection,access,true,false);
            Source source=sourceRow(connection,access,sourceId,false);
            Map<String,Object> dto=sourceDto(connection,source);
            if(!"PARSED".equals(source.parse()))throw new DomainException(409,"IMPORT_PARSE_REQUIRED","请先完成当前来源的工作表选择与解析");
            @SuppressWarnings("unchecked") Map<String,Object> stored=(Map<String,Object>)dto.get("parsed_artifact");
            if(stored==null)throw unavailable();
            ParsedArtifact parsed=parsedArtifact(stored);
            Set<String> names=new LinkedHashSet<>();
            for(var row:parsed.rows())for(var cell:row.cells())
                if(cell.columnIndex()==0&&cell.kind()!=ParsedArtifact.CellKind.ERROR&&cell.kind()!=ParsedArtifact.CellKind.BLANK&&cell.lexical()!=null&&!cell.lexical().isEmpty())names.add(cell.lexical());
            Set<String> existing=new HashSet<>();
            if(!names.isEmpty()){
                java.sql.Array values=connection.createArrayOf("varchar",names.toArray(String[]::new));
                try(PreparedStatement statement=connection.prepareStatement("SELECT name FROM public.mop_asset WHERE tenant_id=?::uuid AND name=ANY(?)")){
                    statement.setString(1,access.tenantId());statement.setArray(2,values);
                    try(ResultSet rows=statement.executeQuery()){while(rows.next())existing.add(rows.getString("name"));}
                }finally{values.free();}
            }
            return new AssetNameSnapshot(dto,existing);
        });
    }
    @Override public Map<String,Object> operation(Access access,String operationId){
        if(!uuid(operationId))throw missing();return read(access,connection->{
            try(PreparedStatement statement=prepare(connection,"SELECT o.* FROM public.mop_asset_import_operation o JOIN public.mop_asset_import_source s ON s.tenant_id=o.tenant_id AND s.id=o.source_id AND s.created_by=o.created_by WHERE o.tenant_id=?::uuid AND o.id=?::uuid AND o.created_by=?::uuid",access.tenantId(),operationId,access.actorId());ResultSet rows=statement.executeQuery()){
                if(!rows.next())throw missing();Map<String,Object> dto=new LinkedHashMap<>();for(String field:List.of("id","tenant_id","source_id","created_by","kind","status","step","result_artifact_id"))dto.put(field,rows.getString(field));dto.put("error",jsonValue(rows.getString("error")));times(dto,rows);dto.put("result_ref","/api/v1/assets/import-sources/"+rows.getString("source_id"));return dto;
            }
        });
    }
    private record Source(String id,String tenant,String actor,String key,ImportFormat format,long size,String sha,long version,String storage,String parse,String storeOperation,String parseOperation,String artifact,String mapping,Map<String,Object> dto){}

    /** Execution uses this transaction-bound artifact, never an earlier read-only preview. */
    record ExecutionSource(long version, String artifactId, String parseHash, ParsedArtifact parsed) {}

    void authorizeExecution(Access access, String sourceId) {
        command(access, connection -> {
            if (!uuid(sourceId)) {
                throw missing();
            }
            sourceRow(connection, access, sourceId, true);
            return null;
        });
    }

    ExecutionSource executionSource(Access access, String sourceId) {
        return command(access, connection -> {
            if (!uuid(sourceId)) {
                throw missing();
            }
            Source source = sourceRow(connection, access, sourceId, true);
            if (!"READY".equals(source.storage()) || !"PARSED".equals(source.parse())) {
                throw state();
            }
            Map<String, Object> stored = artifact(connection, source);
            if (stored == null || !AssetImportSchema.VERSION.equals(stored.get("schema_version"))) {
                throw unavailable();
            }
            ParsedArtifact parsed = parsedArtifact(stored);
            Map<String, Object> binding = new LinkedHashMap<>();
            binding.put("tenant_id", access.tenantId());
            binding.put("source_id", sourceId);
            binding.put("source_sha256", source.sha());
            binding.put("selection", stored.get("selection"));
            binding.put("schema_version", AssetImportSchema.VERSION);
            binding.put("artifact", mapper.valueToTree(parsed));
            if (!canonicalHash(mapper.valueToTree(binding)).equals(stored.get("parse_hash"))) {
                throw unavailable();
            }
            return new ExecutionSource(source.version(), source.artifact(), (String) stored.get("parse_hash"), parsed);
        });
    }
    private Source sourceRow(Connection connection,Access access,String id,boolean locked)throws SQLException{
        try(PreparedStatement statement=prepare(connection,SOURCE_SELECT+(locked?" FOR UPDATE OF s":""),access.tenantId(),id,access.actorId());ResultSet rows=statement.executeQuery()){
            if(!rows.next())throw missing();Map<String,Object> dto=new LinkedHashMap<>();for(String field:List.of("id","tenant_id","created_by","purpose","original_name","declared_format","sha256","storage_status","parse_status","store_operation_id","parse_operation_id","latest_artifact_id","latest_mapping_id"))dto.put(field,rows.getString(field));dto.put("version",rows.getLong("version"));dto.put("size_bytes",rows.getLong("size_bytes"));times(dto,rows);
            return new Source(id,access.tenantId(),access.actorId(),rows.getString("object_key"),ImportFormat.valueOf(rows.getString("declared_format")),rows.getLong("size_bytes"),rows.getString("sha256"),rows.getLong("version"),rows.getString("storage_status"),rows.getString("parse_status"),rows.getString("store_operation_id"),rows.getString("parse_operation_id"),rows.getString("latest_artifact_id"),rows.getString("latest_mapping_id"),dto);
        }
    }
    private Map<String,Object> sourceDto(Connection connection,Source source)throws SQLException{
        Map<String,Object> dto=new LinkedHashMap<>(source.dto());dto.put("parsed_artifact",artifact(connection,source));dto.put("mapping",mapping(connection,source));dto.put("business_status","CONFIG_PENDING");dto.put("can_import",false);dto.put("question_ids",List.of("Q20","Q31"));dto.put("effective_rule_versions",null);dto.put("quota_reservation",null);dto.put("scan_status","UNSCANNED");dto.put("production_approval","UNVERIFIED");dto.put("retention_until",null);return dto;
    }
    private Map<String,Object> artifact(Connection connection,Source source)throws SQLException{
        if(source.artifact()==null)return null;
        try(PreparedStatement statement=prepare(connection,"SELECT a.* FROM public.mop_asset_import_artifact a WHERE a.tenant_id=?::uuid AND a.source_id=?::uuid AND a.id=?::uuid AND a.operation_id=?::uuid AND a.source_sha256=?",source.tenant(),source.id(),source.artifact(),source.parseOperation(),source.sha());ResultSet rows=statement.executeQuery()){
            if(!rows.next())throw unavailable();Map<String,Object> dto=object(rows.getString("artifact_json"));for(String field:List.of("id","tenant_id","source_id","operation_id","parse_hash","source_sha256","schema_version"))dto.put(field,rows.getString(field));dto.put("selection",jsonValue(rows.getString("selection_json")));dto.put("created_at",timestamp(rows,"created_at"));return dto;
        }
    }
    private ParsedArtifact parsedArtifact(Map<String,Object> stored){
        Map<String,Object> values=new LinkedHashMap<>(stored);for(String key:List.of("id","tenant_id","source_id","operation_id","parse_hash","source_sha256","schema_version","selection","created_at"))values.remove(key);
        try{return mapper.convertValue(values,ParsedArtifact.class);}catch(IllegalArgumentException failure){throw unavailable();}
    }
    private Map<String,Object> mapping(Connection connection,Source source)throws SQLException{
        if(source.mapping()==null)return null;
        try(PreparedStatement statement=prepare(connection,"SELECT m.* FROM public.mop_asset_import_mapping m WHERE m.tenant_id=?::uuid AND m.source_id=?::uuid AND m.id=?::uuid AND m.artifact_id=?::uuid AND m.created_by=?::uuid",source.tenant(),source.id(),source.mapping(),source.artifact(),source.actor());ResultSet rows=statement.executeQuery()){
            if(!rows.next())throw unavailable();Map<String,Object> dto=new LinkedHashMap<>();for(String field:List.of("id","tenant_id","source_id","artifact_id","source_parse_hash","preview_hash","schema_version"))dto.put(field,rows.getString(field));dto.put("version",rows.getLong("version"));for(String field:List.of("target_source_columns","ignored_source_columns","mapped_raw_rows"))dto.put(field,jsonValue(rows.getString(field)));dto.put("precheck_status","NOT_RUN");dto.put("created_at",timestamp(rows,"created_at"));return dto;
        }
    }
    private long nextMappingVersion(Connection connection,Source source)throws SQLException{
        try(PreparedStatement statement=prepare(connection,"SELECT COALESCE(MAX(version),0)+1 FROM public.mop_asset_import_mapping WHERE tenant_id=?::uuid AND source_id=?::uuid",source.tenant(),source.id());ResultSet rows=statement.executeQuery()){rows.next();return rows.getLong(1);}
    }
    static List<Map<String,Object>> mappedRows(ParsedArtifact artifact,MappingRequest mapping){
        List<Map<String,Object>> rows=new ArrayList<>();for(var raw:artifact.rows()){
            Map<Integer,ParsedArtifact.SourceCell> cellsByIndex=new HashMap<>();for(var cell:raw.cells())cellsByIndex.put(cell.columnIndex(),cell);
            Map<String,Object> cells=new LinkedHashMap<>();for(Column column:Column.values()){Integer index=mapping.targetSourceColumns().get(column);cells.put(column.name(),index==null?null:cellsByIndex.get(index));}
            Map<String,Object> row=new LinkedHashMap<>();row.put("logical_record_number",raw.logicalRecordNumber());row.put("source_row_number",raw.sourceRowNumber());row.put("physical_start_line",raw.physicalStartLine());row.put("physical_end_line",raw.physicalEndLine());row.put("cells",cells);rows.add(row);
        }return rows;
    }
    @Override public boolean ready(){
        try(Connection connection=dataSource.getConnection()){
            String permissions="""
                WITH required(table_name,updatable) AS (VALUES
                  ('mop_asset_import_source',ARRAY['storage_status','parse_status','parse_operation_id','latest_artifact_id','latest_mapping_id','version','updated_at']::text[]),
                  ('mop_asset_import_operation',ARRAY['status','step','result_artifact_id','error','updated_at']::text[]),
                  ('mop_asset_import_job',ARRAY['state','lease_owner','lease_until','fencing_token','attempts','next_at']::text[]),
                  ('mop_asset_import_artifact',ARRAY[]::text[]),('mop_asset_import_mapping',ARRAY[]::text[])
                ), objects AS (
                  SELECT r.*,c.oid,c.relowner FROM required r LEFT JOIN pg_catalog.pg_class c
                    ON c.relname=r.table_name AND c.relnamespace='public'::regnamespace AND c.relkind='r'
                )
                SELECT current_database()='mop_test' AND current_user='mop_test_runtime'
                  AND NOT has_schema_privilege(current_user,'public','CREATE')
                  AND NOT has_database_privilege(current_user,current_database(),'CREATE')
                  AND (SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_catalog.pg_roles WHERE rolname=current_user)
                  AND COALESCE(bool_and(oid IS NOT NULL AND NOT pg_has_role(current_user,relowner,'MEMBER')
                    AND has_table_privilege(current_user,oid,'SELECT') AND has_table_privilege(current_user,oid,'INSERT')
                    AND NOT has_table_privilege(current_user,oid,'UPDATE') AND NOT has_table_privilege(current_user,oid,'DELETE')
                    AND NOT has_table_privilege(current_user,oid,'TRUNCATE') AND NOT has_table_privilege(current_user,oid,'REFERENCES')
                    AND NOT has_table_privilege(current_user,oid,'TRIGGER')
                    AND (SELECT COALESCE(bool_and(has_column_privilege(current_user,a.attrelid,a.attnum,'UPDATE')=(a.attname=ANY(o.updatable))),false)
                         FROM pg_catalog.pg_attribute a WHERE a.attrelid=o.oid AND a.attnum>0 AND NOT a.attisdropped)
                    AND (SELECT count(*)=cardinality(o.updatable) FROM pg_catalog.pg_attribute a
                         WHERE a.attrelid=o.oid AND a.attnum>0 AND NOT a.attisdropped AND a.attname=ANY(o.updatable))
                    AND (o.table_name<>'mop_asset_import_job' OR (SELECT count(*)=3 FROM pg_catalog.pg_attribute a
                         WHERE a.attrelid=o.oid AND a.attname IN ('content_size','content_sha256','store_operation_id') AND a.attgenerated='s'))),false)
                FROM objects o
                """;
            try(PreparedStatement statement=connection.prepareStatement(permissions)){statement.setQueryTimeout(3);try(ResultSet rows=statement.executeQuery()){if(!rows.next()||!rows.getBoolean(1))return false;}}
            try(PreparedStatement statement=connection.prepareStatement("SELECT s.purpose,s.object_key,s.store_kind,s.parse_kind,s.storage_status,s.parse_status,s.permission_version,s.store_operation_id,s.parse_operation_id,s.latest_artifact_id,s.latest_mapping_id,s.version,o.kind,o.status,o.step,o.result_artifact_id,o.error,j.content,j.content_size,j.content_sha256,j.store_operation_id,j.selection_json,j.lease_owner,j.lease_until,j.fencing_token,j.attempts,j.next_at,a.parse_hash,a.source_sha256,a.selection_json,a.artifact_json,a.schema_version,m.source_parse_hash,m.preview_hash,m.target_source_columns,m.ignored_source_columns,m.mapped_raw_rows FROM public.mop_asset_import_source s JOIN public.mop_asset_import_operation o ON o.tenant_id=s.tenant_id AND o.source_id=s.id JOIN public.mop_asset_import_job j ON j.operation_id=o.id JOIN public.mop_asset_import_artifact a ON a.source_id=s.id JOIN public.mop_asset_import_mapping m ON m.source_id=s.id WHERE FALSE")){statement.setQueryTimeout(3);statement.executeQuery().close();}return true;
        }catch(SQLException failure){return false;}
    }
    private record Job(String id,String tenant,String source,String actor,String operation,String kind,String key,ImportFormat format,long size,String sha,byte[] content,ParseSelection selection,long permissionVersion,long token,int attempts){}
    private Job claim(String worker,Set<String> scope){return transaction(connection->{
        String scoped=scope==null?"":" AND s.id::text IN ("+String.join(",",Collections.nCopies(scope.size(),"?"))+")";
        String sql="SELECT j.id AS job_id,j.content,j.selection_json,j.fencing_token,j.attempts,o.id AS operation_id,o.kind,o.permission_version AS job_permission_version,s.* FROM public.mop_asset_import_job j JOIN public.mop_asset_import_operation o ON o.tenant_id=j.tenant_id AND o.source_id=j.source_id AND o.id=j.operation_id AND o.kind=j.kind JOIN public.mop_asset_import_source s ON s.tenant_id=o.tenant_id AND s.id=o.source_id AND s.created_by=o.created_by WHERE ((j.state='PENDING' AND j.next_at<=clock_timestamp()) OR (j.state='LEASED' AND j.lease_until<=clock_timestamp())) AND ((j.kind='STORE_RAW' AND s.storage_status='PENDING' AND s.store_operation_id=o.id) OR (j.kind='PARSE' AND s.storage_status='READY' AND s.parse_status='PENDING' AND s.parse_operation_id=o.id))"+scoped+" ORDER BY j.created_at,j.id LIMIT 1 FOR UPDATE OF j SKIP LOCKED";
        Job job;try(PreparedStatement statement=prepare(connection,sql,scope==null?new Object[0]:scope.toArray());ResultSet rows=statement.executeQuery()){
            if(!rows.next())return null;String selected=rows.getString("selection_json");ParseSelection selection=selected==null?null:selection(selected);
            job=new Job(rows.getString("job_id"),rows.getString("tenant_id"),rows.getString("id"),rows.getString("created_by"),rows.getString("operation_id"),rows.getString("kind"),rows.getString("object_key"),ImportFormat.valueOf(rows.getString("declared_format")),rows.getLong("size_bytes"),rows.getString("sha256"),rows.getBytes("content"),selection,rows.getLong("job_permission_version"),rows.getLong("fencing_token")+1,rows.getInt("attempts")+1);
        }
        if(execute(connection,"UPDATE public.mop_asset_import_job SET state='LEASED',lease_owner=?,lease_until=clock_timestamp()+interval '180 seconds',fencing_token=?,attempts=?,next_at=NULL WHERE tenant_id=?::uuid AND id=?::uuid",worker,job.token(),job.attempts(),job.tenant(),job.id())!=1)throw unavailable();
        updateOperation(connection,job,"RUNNING","STORE_RAW".equals(job.kind())?"STORING":"PARSING",null,null);return job;
    });}
    private <T>T transaction(SqlWork<T> work){return platform.importJobTransaction(()->{try{return work.run(platform.importCommandConnection());}catch(SQLException failure){throw unavailable();}});}
    private boolean authorized(Job job){return platform.membership(job.actor(),job.tenant()).filter(m->m.permissionVersion()==job.permissionVersion()&&m.capabilities().contains("assets:write")).isPresent();}
    private boolean lease(Connection connection,Job job,String worker)throws SQLException{
        try(PreparedStatement statement=prepare(connection,"SELECT 1 FROM public.mop_asset_import_job WHERE tenant_id=?::uuid AND id=?::uuid AND operation_id=?::uuid AND state='LEASED' AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp() FOR UPDATE",job.tenant(),job.id(),job.operation(),worker,job.token());ResultSet rows=statement.executeQuery()){return rows.next();}
    }
    private boolean allowedBeforeExecution(Job job,String worker){return transaction(c->lease(c,job,worker)&&authorized(job));}
    @Override public boolean processNext(String worker,AssetImportObjectStore store,AssetImportParser parser){return process(worker,store,parser,null);}
    /** Only newly accepted explicit import IDs may be consumed by injected integration-test adapters. */
    boolean processNext(String worker,AssetImportObjectStore store,AssetImportParser parser,Set<String> allowedSourceIds){
        if(allowedSourceIds==null||allowedSourceIds.isEmpty()||allowedSourceIds.size()>100||allowedSourceIds.stream().anyMatch(id->!uuid(id)))throw new IllegalArgumentException("Explicit import integration scope required");return process(worker,store,parser,Collections.unmodifiableSet(new TreeSet<>(allowedSourceIds)));
    }
    private boolean process(String worker,AssetImportObjectStore store,AssetImportParser parser,Set<String> scope){
        if(worker==null||!worker.matches("[A-Za-z0-9_-]{1,64}")||store==null||parser==null)throw new IllegalArgumentException("Import worker, store and parser are required");
        if(Thread.currentThread().isInterrupted())return false;
        Job job=claim(worker,scope);if(job==null)return false;String outcome="AUTHORIZATION_REVOKED";ParsedArtifact parsed=null;
        if(allowedBeforeExecution(job,worker)){
            try{
                if("STORE_RAW".equals(job.kind())){verify(job.content(),job.size(),job.sha());store.storeImmutable(job.key(),job.content(),job.format(),job.sha());verify(store.read(job.key(),job.size(),job.sha()),job.size(),job.sha());outcome="STORED";}
                else{byte[] bytes=store.read(job.key(),job.size(),job.sha());verify(bytes,job.size(),job.sha());if(!allowedBeforeExecution(job,worker))outcome="AUTHORIZATION_REVOKED";else{parsed=parseLocal(bytes,job.selection(),parser);if(parsed.detectedFormat()!=job.format()||!ImportLimits.RESOURCE_POLICY_VERSION.equals(parsed.resourcePolicyVersion()))throw new IllegalArgumentException("Invalid parser result");outcome=parsed.state().name();}}
            }catch(ParseFailure failure){outcome=failure.code()==ParseFailure.Code.INTERNAL_PARSER_FAILURE?"RETRY_WAIT":"PARSE_INVALID";}
            catch(DomainException failure){outcome="FILE_INTEGRITY_CONFLICT".equals(failure.code)?"STORAGE_CONFLICT":failure.status>=400&&failure.status<500?"PARSE_INVALID":"RETRY_WAIT";}
            catch(IllegalArgumentException failure){outcome="PARSE_INVALID";}catch(IOException|RuntimeException failure){outcome="RETRY_WAIT";}
        }
        // An interrupted worker leaves its lease for a live worker to reclaim; no success is committed.
        if(Thread.currentThread().isInterrupted())return true;
        try{finish(job,worker,outcome,parsed);}catch(LostLease ignored){/* The current fenced lease owns every database write. */}return true;
    }
    private static ParsedArtifact parseLocal(byte[] bytes,ParseSelection selection,AssetImportParser parser)throws IOException{
        Path path=Files.createTempFile("mop-import-",".source");try{Files.write(path,bytes);return Objects.requireNonNull(parser.parse(path,selection));}finally{Files.deleteIfExists(path);}
    }
    private static void verify(byte[] bytes,long size,String digest){if(bytes==null||bytes.length!=size||!sha(bytes).equals(digest))throw new DomainException(409,"FILE_INTEGRITY_CONFLICT","原始导入源大小或摘要不符");}
    private boolean finish(Job job,String worker,String proposed,ParsedArtifact parsed){return transaction(connection->{
        if(Thread.currentThread().isInterrupted())throw new LostLease();
        if(!lease(connection,job,worker))return false;
        String outcome=authorized(job)?proposed:"AUTHORIZATION_REVOKED";
        boolean succeeded=Set.of("STORED","PARSED","SELECTION_REQUIRED").contains(outcome),retry="RETRY_WAIT".equals(outcome);
        Access access=new Access(job.actor(),job.tenant(),job.permissionVersion(),"import-job-"+job.id()+"-"+job.token());Source source=sourceRow(connection,access,job.source(),true);
        if(("STORE_RAW".equals(job.kind())&&!job.operation().equals(source.storeOperation()))||("PARSE".equals(job.kind())&&!job.operation().equals(source.parseOperation())))throw new LostLease();
        String artifactId=null;
        if(succeeded&&"PARSE".equals(job.kind())){
            if(parsed==null)throw unavailable();artifactId=uuid();JsonNode artifact=mapper.valueToTree(parsed);Map<String,Object> binding=new LinkedHashMap<>();binding.put("tenant_id",job.tenant());binding.put("source_id",job.source());binding.put("source_sha256",job.sha());binding.put("selection",job.selection());binding.put("schema_version",AssetImportSchema.VERSION);binding.put("artifact",artifact);String hash=canonicalHash(mapper.valueToTree(binding));
            execute(connection,"INSERT INTO public.mop_asset_import_artifact(id,tenant_id,source_id,operation_id,parse_hash,source_sha256,selection_json,artifact_json,schema_version) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,?,?::jsonb,?::jsonb,?)",artifactId,job.tenant(),job.source(),job.operation(),hash,job.sha(),jsonText(job.selection()),artifact.toString(),AssetImportSchema.VERSION);
        }
        if(Thread.currentThread().isInterrupted())throw new LostLease();
        if(execute(connection,"UPDATE public.mop_asset_import_job SET state=?,lease_owner=NULL,lease_until=NULL,next_at=CASE WHEN ? THEN clock_timestamp()+(? * interval '1 second') ELSE NULL END WHERE tenant_id=?::uuid AND id=?::uuid AND state='LEASED' AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp()",succeeded?"SUCCEEDED":retry?"PENDING":"FAILED",retry,Math.min(300,5*(1<<Math.min(job.attempts()-1,6))),job.tenant(),job.id(),worker,job.token())!=1)throw new LostLease();
        if("STORE_RAW".equals(job.kind())){
            if(execute(connection,"UPDATE public.mop_asset_import_source SET storage_status=?,version=version+1,updated_at=clock_timestamp() WHERE tenant_id=?::uuid AND id=?::uuid AND store_operation_id=?::uuid AND storage_status='PENDING'",succeeded?"READY":retry?"PENDING":"FAILED",job.tenant(),job.source(),job.operation())!=1)throw new LostLease();
        }else if(execute(connection,"UPDATE public.mop_asset_import_source SET parse_status=?,latest_artifact_id=?::uuid,latest_mapping_id=NULL,version=version+1,updated_at=clock_timestamp() WHERE tenant_id=?::uuid AND id=?::uuid AND parse_operation_id=?::uuid AND parse_status='PENDING'",succeeded?outcome:retry?"PENDING":"FAILED",artifactId,job.tenant(),job.source(),job.operation())!=1)throw new LostLease();
        updateOperation(connection,job,succeeded?"SUCCEEDED":retry?"PENDING":"FAILED",outcome,artifactId,error(outcome));
        platform.audit(job.actor(),job.tenant(),"asset.import.worker."+(succeeded?"completed":retry?"retry":"failed"),"asset_import_source",job.source(),succeeded?"SUCCEEDED":retry?"PENDING":"FAILED",outcome,access.traceId());
        if(Thread.currentThread().isInterrupted())throw new LostLease();return true;
    });}
    private void updateOperation(Connection connection,Job job,String status,String step,String artifact,Map<String,Object> error)throws SQLException{
        if(execute(connection,"UPDATE public.mop_asset_import_operation SET status=?,step=?,result_artifact_id=?::uuid,error=?::jsonb,updated_at=clock_timestamp() WHERE tenant_id=?::uuid AND source_id=?::uuid AND id=?::uuid AND kind=?",status,step,artifact,error==null?null:jsonText(error),job.tenant(),job.source(),job.operation(),job.kind())!=1)throw unavailable();
    }
    private static Map<String,Object> error(String outcome){
        if(Set.of("STORED","PARSED","SELECTION_REQUIRED").contains(outcome))return null;boolean retry="RETRY_WAIT".equals(outcome);
        String code=switch(outcome){case "AUTHORIZATION_REVOKED"->"ACTION_FORBIDDEN";case "STORAGE_CONFLICT"->"FILE_INTEGRITY_CONFLICT";case "PARSE_INVALID"->"IMPORT_SOURCE_INVALID";default->"UPSTREAM_UNAVAILABLE";};
        String message=switch(outcome){case "AUTHORIZATION_REVOKED"->"导入授权已变化，任务已停止";case "STORAGE_CONFLICT"->"导入源摘要不符，禁止覆盖";case "PARSE_INVALID"->"原始导入源或解析选择无效";default->"指定组件暂不可用，耐久任务等待重试";};return Map.of("code",code,"message",message,"retryable",retry,"question_ids",List.of());
    }
    private static final class LostLease extends RuntimeException{}
    private ParseSelection selection(String value){try{return mapper.readValue(value,ParseSelection.class);}catch(Exception failure){throw unavailable();}}
    private static PreparedStatement prepare(Connection connection,String sql,Object... parameters)throws SQLException{
        PreparedStatement statement=connection.prepareStatement(sql);try{for(int i=0;i<parameters.length;i++){Object value=parameters[i];if(value instanceof byte[] bytes)statement.setBytes(i+1,bytes);else statement.setObject(i+1,value);}return statement;}catch(SQLException|RuntimeException failure){statement.close();throw failure;}
    }
    private static int execute(Connection connection,String sql,Object... parameters)throws SQLException{try(PreparedStatement statement=prepare(connection,sql,parameters)){return statement.executeUpdate();}}
    private String jsonText(Object value){try{return mapper.writeValueAsString(value);}catch(Exception failure){throw unavailable();}}
    private Object jsonValue(String value){if(value==null)return null;try{return mapper.readValue(value,Object.class);}catch(Exception failure){throw unavailable();}}
    @SuppressWarnings("unchecked") private Map<String,Object> object(String value){Object decoded=jsonValue(value);if(!(decoded instanceof Map))throw unavailable();return new LinkedHashMap<>((Map<String,Object>)decoded);}
    private static void times(Map<String,Object> dto,ResultSet rows)throws SQLException{dto.put("created_at",timestamp(rows,"created_at"));dto.put("updated_at",timestamp(rows,"updated_at"));}
    private static String timestamp(ResultSet rows,String field)throws SQLException{Timestamp timestamp=rows.getTimestamp(field);return timestamp==null?null:timestamp.toInstant().toString();}
    static String canonicalHash(JsonNode node){return sha(canonical(node).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static JsonNode canonical(JsonNode node){
        if(node.isObject()){ObjectNode result=JsonNodeFactory.instance.objectNode();TreeSet<String> names=new TreeSet<>();node.fieldNames().forEachRemaining(names::add);for(String name:names)result.set(name,canonical(node.get(name)));return result;}
        if(node.isArray()){ArrayNode result=JsonNodeFactory.instance.arrayNode();for(JsonNode item:node)result.add(canonical(item));return result;}return node;
    }
}
