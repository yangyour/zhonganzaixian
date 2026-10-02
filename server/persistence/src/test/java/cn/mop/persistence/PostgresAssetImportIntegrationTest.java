package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.assetimport.AssetImportPrechecker.Column;
import cn.mop.domain.assetimport.source.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual designated PostgreSQL tests, explicitly enabled only after reviewed V6 application.
 * Injected store/parser adapters test transaction boundaries, not MinIO/parser integration.
 * Each adapter consumes only fresh explicit source IDs. No migration, service start, cleanup,
 * alternate database, fixture repository or another project's outbox is used by these tests.
 */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresAssetImportIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    static final byte[] RAW="备注,额外\r\nMOP-FAKE raw value,ignored\r\n".getBytes(StandardCharsets.UTF_8);
    static final ParseSelection CSV=new ParseSelection(ImportFormat.CSV,"UTF-8",",",null,1);
    static HikariDataSource pool;static ObjectMapper mapper;static PostgresPlatformRepository platform;static PostgresAssetImportRepository repository;static String actor,admin,viewer,other;
    @BeforeAll static void connect(){
        assertTrue(required("SPRING_DATASOURCE_URL").matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",required("SPRING_DATASOURCE_USERNAME"));
        HikariConfig config=new HikariConfig();config.setJdbcUrl(required("SPRING_DATASOURCE_URL"));config.setUsername(required("SPRING_DATASOURCE_USERNAME"));config.setPassword(required("SPRING_DATASOURCE_PASSWORD"));config.setMaximumPoolSize(8);config.setConnectionTimeout(5000);pool=new HikariDataSource(config);
        mapper=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);platform=new PostgresPlatformRepository(pool,mapper);repository=new PostgresAssetImportRepository(pool,mapper,platform);
        assertTrue(platform.dependencyReady());assertTrue(repository.ready(),"Independent review/apply V6 is required; this test never migrates");actor=user("租户 A 管理员（测试身份）");admin=user("平台管理员（测试身份）");viewer=user("租户 A 只读用户（测试身份）");other=user("租户 B 只读用户（测试身份）");
    }
    @AfterAll static void close(){if(pool!=null)pool.close();}
    static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Missing private setting "+key);return value;}
    static String user(String name){return platform.users().stream().filter(u->name.equals(u.displayName())).findFirst().orElseThrow().id();}
    static AssetImportSourceRepository.Access access(String actor,String tenant){return new AssetImportSourceRepository.Access(actor,tenant,platform.membership(actor,tenant).orElseThrow().permissionVersion(),"import-integration");}
    static AssetImportSourceRepository.Access access(){return access(actor,TENANT);}
    record Accepted(String source,String operation){}
    static Accepted accept(String key){return accept(key,RAW);}
    static Accepted accept(String key,byte[] bytes){var access=access();Object result=platform.idempotent(actor,TENANT,"/api/v1/assets/import-sources",key,PostgresAssetImportRepository.sha(bytes),access.permissionVersion(),()->repository.acceptSource(access,"MOP-FAKE.csv",ImportFormat.CSV,bytes));JsonNode json=mapper.valueToTree(result);return new Accepted(json.path("source_id").asText(),json.path("operation_id").asText());}
    static Map<String,Object> source(Accepted accepted){return repository.source(access(),accepted.source());}
    static Map<String,Object> operation(Accepted accepted){return repository.operation(access(),accepted.operation());}
    static Accepted parse(Accepted accepted){Map<String,Object> current=source(accepted);var access=access();Object result=platform.idempotent(actor,TENANT,"/api/v1/assets/import-sources/"+accepted.source()+"/parse",UUID.randomUUID().toString(),"parse-"+current.get("version"),access.permissionVersion(),()->repository.requestParse(access,accepted.source(),((Number)current.get("version")).longValue(),CSV));JsonNode json=mapper.valueToTree(result);return new Accepted(accepted.source(),json.path("operation_id").asText());}
    @SuppressWarnings("unchecked") static Map<String,Object> artifact(Accepted accepted){return (Map<String,Object>)source(accepted).get("parsed_artifact");}
    static ParsedArtifact rawParsed(){return new ParsedArtifact(ParsedArtifact.State.PARSED,ImportFormat.CSV,List.of(new ParsedArtifact.SheetInfo(0,"CSV",false)),0,1,ParsedArtifact.DateEpoch.NOT_APPLICABLE,ParsedArtifact.columns(List.of("备注","额外")),List.of(new ParsedArtifact.SourceRow(1,2,2L,2L,List.of(new ParsedArtifact.SourceCell(0,ParsedArtifact.CellKind.TEXT,"MOP-FAKE raw value","MOP-FAKE raw value",null,null),new ParsedArtifact.SourceCell(1,ParsedArtifact.CellKind.TEXT,"ignored","ignored",null,null)))),"postgres-boundary-test",ImportLimits.RESOURCE_POLICY_VERSION);}
    static AssetImportParser parser(){return (path,selection)->{try{assertArrayEquals(RAW,Files.readAllBytes(path));}catch(Exception failure){throw new IllegalStateException("Test local source check failed");}assertEquals(CSV,selection);return rawParsed();};}
    static AssetImportParser neverParser(){return (path,selection)->{throw new AssertionError("Parser must not execute for this test");};}
    static final class Store implements AssetImportObjectStore {
        final Map<String,byte[]> originals=new ConcurrentHashMap<>();final AtomicInteger creates=new AtomicInteger();
        public boolean ready(){return true;}
        public void storeImmutable(String key,byte[] bytes,ImportFormat format,String hash){assertEquals(ImportFormat.CSV,format);assertEquals(hash,PostgresAssetImportRepository.sha(bytes));byte[] previous=originals.putIfAbsent(key,bytes.clone());if(previous==null)creates.incrementAndGet();else if(!Arrays.equals(previous,bytes))throw new DomainException(409,"FILE_INTEGRITY_CONFLICT","MOP-FAKE integrity conflict");}
        public byte[] read(String key,long size,String hash){byte[] bytes=originals.get(key);if(bytes==null)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","MOP-FAKE absent storage");assertEquals(size,bytes.length);assertEquals(hash,PostgresAssetImportRepository.sha(bytes));return bytes.clone();}
    }
    static void ready(Accepted accepted,Store store){assertTrue(repository.processNext("import-test-store",store,neverParser(),Set.of(accepted.source())));assertEquals("READY",source(accepted).get("storage_status"));}
    static long number(Map<String,Object> map,String key){return ((Number)map.get(key)).longValue();}
    static int update(String sql,Object... values)throws SQLException{try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement(sql)){for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);return statement.executeUpdate();}}
    static long count(String table,String column,String id)throws SQLException{assertTrue(Set.of("mop_asset_import_source","mop_asset_import_operation","mop_asset_import_job","mop_asset_import_artifact","mop_asset_import_mapping").contains(table));assertTrue(Set.of("id","source_id","operation_id").contains(column));try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT count(*) FROM public."+table+" WHERE "+column+"=?::uuid")){statement.setString(1,id);try(ResultSet rows=statement.executeQuery()){rows.next();return rows.getLong(1);}}}
    @Test void acceptanceOriginalBytesOperationJobAuditAndReplayCommitOnceAndSurviveRepositoryRestart()throws Exception{
        String key=UUID.randomUUID().toString();Accepted accepted=accept(key);assertEquals(accepted,accept(key));assertEquals(1,count("mop_asset_import_source","id",accepted.source()));assertEquals(1,count("mop_asset_import_job","source_id",accepted.source()));assertEquals(1,count("mop_asset_import_operation","id",accepted.operation()));assertEquals("PENDING",source(accepted).get("storage_status"));assertEquals("NOT_REQUESTED",source(accepted).get("parse_status"));assertNull(source(accepted).get("parsed_artifact"));assertEquals("PENDING",operation(accepted).get("status"));
        PostgresPlatformRepository restarted=new PostgresPlatformRepository(pool,mapper);var imports=new PostgresAssetImportRepository(pool,mapper,restarted);assertEquals(source(accepted),imports.source(access(),accepted.source()));
        try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT j.content,s.sha256,s.object_key,(SELECT count(*) FROM public.mop_audit WHERE tenant_id=s.tenant_id AND payload->>'resource_id'=s.id::text AND payload->>'action'='asset.import.source.accept') AS audit_count FROM public.mop_asset_import_source s JOIN public.mop_asset_import_job j ON j.tenant_id=s.tenant_id AND j.source_id=s.id WHERE s.id=?::uuid")){statement.setString(1,accepted.source());try(ResultSet rows=statement.executeQuery()){assertTrue(rows.next());assertArrayEquals(RAW,rows.getBytes("content"));assertEquals(PostgresAssetImportRepository.sha(RAW),rows.getString("sha256"));assertEquals("asset-import-sources/"+TENANT+"/"+accepted.source(),rows.getString("object_key"));assertEquals(1,rows.getLong("audit_count"));}}
        assertEquals(409,assertThrows(DomainException.class,()->accept(key,new byte[]{1})).status);
    }
    @Test void rollbackAfterAcceptanceCannotLeaveSourceJobOperationAuditOrReplay()throws Exception{
        var access=access();String key=UUID.randomUUID().toString();Accepted[] accepted={null};assertThrows(IllegalStateException.class,()->platform.idempotent(actor,TENANT,"/asset-import-rollback",key,"rollback",access.permissionVersion(),()->{Map<String,Object> result=repository.acceptSource(access,"MOP-FAKE.csv",ImportFormat.CSV,RAW);accepted[0]=new Accepted((String)result.get("source_id"),(String)result.get("operation_id"));throw new IllegalStateException("MOP-FAKE abort after acceptance");}));
        assertEquals(0,count("mop_asset_import_source","id",accepted[0].source()));assertEquals(0,count("mop_asset_import_job","source_id",accepted[0].source()));assertEquals(0,count("mop_asset_import_operation","id",accepted[0].operation()));
    }
    @Test void sourceAndOperationsAreCreatorOnlyAndReadForeignPhotoOrRandomIdsAs404()throws Exception{
        Accepted accepted=accept(UUID.randomUUID().toString());for(var reader:List.of(access(admin,TENANT),access(viewer,TENANT),access(other,OTHER))){assertEquals(404,assertThrows(DomainException.class,()->repository.source(reader,accepted.source())).status);assertEquals(404,assertThrows(DomainException.class,()->repository.operation(reader,accepted.operation())).status);}
        String photo=UUID.randomUUID().toString();assertEquals(404,assertThrows(DomainException.class,()->repository.source(access(),photo)).status);assertEquals(404,assertThrows(DomainException.class,()->repository.operation(access(),photo)).status);
        var customer=access(viewer,TENANT);assertEquals(403,assertThrows(DomainException.class,()->platform.idempotent(viewer,TENANT,"/asset-import-role",UUID.randomUUID().toString(),"fake",customer.permissionVersion(),()->repository.acceptSource(customer,"MOP-FAKE.csv",ImportFormat.CSV,RAW))).status);
    }
    @Test void verifiedStoreParseAndRepeatedMappingKeepRawDataAndImmutableHistory()throws Exception{
        Accepted accepted=accept(UUID.randomUUID().toString());Store store=new Store();ready(accepted,store);assertEquals(1,store.creates.get());Accepted parse=parse(accepted);assertNull(source(parse).get("parsed_artifact"));assertTrue(repository.processNext("import-test-parser",store,parser(),Set.of(parse.source())));Map<String,Object> artifact=artifact(parse);assertEquals("PARSED",source(parse).get("parse_status"));assertEquals(parse.operation(),artifact.get("operation_id"));assertEquals(PostgresAssetImportRepository.sha(RAW),artifact.get("source_sha256"));assertEquals("NOT_APPLICABLE",artifact.get("date_epoch"));assertEquals(artifact.get("id"),operation(parse).get("result_artifact_id"));
        Map<String,Object> first=saveMapping(parse);assertFalse((boolean)first.get("can_import"));Map<?,?> mapping=(Map<?,?>)first.get("mapping");assertEquals("NOT_RUN",mapping.get("precheck_status"));assertEquals(1L,((Number)mapping.get("version")).longValue());assertEquals(15,((Map<?,?>)mapping.get("target_source_columns")).size());assertNull(((Map<?,?>)mapping.get("target_source_columns")).get("NAME"));assertEquals(List.of(1),mapping.get("ignored_source_columns"));
        Map<?,?> second=(Map<?,?>)saveMapping(parse).get("mapping");assertEquals(2L,((Number)second.get("version")).longValue());assertEquals(mapping.get("preview_hash"),second.get("preview_hash"));assertNotEquals(mapping.get("id"),second.get("id"));assertEquals(2,count("mop_asset_import_mapping","source_id",parse.source()));
        assertEquals(1,count("mop_asset_import_artifact","source_id",parse.source()));assertEquals("SUCCEEDED",operation(parse).get("status"));
    }
    static Map<String,Object> saveMapping(Accepted accepted){Map<String,Object> current=source(accepted);EnumMap<Column,Integer> targets=new EnumMap<>(Column.class);for(Column column:Column.values())targets.put(column,null);targets.put(Column.NOTE,0);MappingRequest request=new MappingRequest(number(current,"version"),(String)artifact(accepted).get("parse_hash"),targets,Set.of(1));var access=access();return (Map<String,Object>)platform.idempotent(actor,TENANT,"/api/v1/assets/import-sources/"+accepted.source()+"/mapping",UUID.randomUUID().toString(),"mapping-"+current.get("version"),access.permissionVersion(),()->repository.saveMapping(access,accepted.source(),request));}
    @Test void staleVersionsHashAndPendingReparseFailWithoutMutatingSource()throws Exception{
        Accepted accepted=accept(UUID.randomUUID().toString());Store store=new Store();ready(accepted,store);Accepted parsed=parse(accepted);long pending=number(source(parsed),"version");var access=access();
        assertEquals(422,assertThrows(DomainException.class,()->platform.idempotent(actor,TENANT,"/pending-parse",UUID.randomUUID().toString(),"pending",access.permissionVersion(),()->repository.requestParse(access,parsed.source(),pending,CSV))).status);
        assertTrue(repository.processNext("import-test-parse-version",store,parser(),Set.of(parsed.source())));Map<String,Object> current=source(parsed);long version=number(current,"version");
        EnumMap<Column,Integer> targets=new EnumMap<>(Column.class);for(Column column:Column.values())targets.put(column,null);targets.put(Column.NOTE,0);
        for(MappingRequest request:List.of(new MappingRequest(version-1,(String)artifact(parsed).get("parse_hash"),targets,Set.of(1)),new MappingRequest(version,"a".repeat(64),targets,Set.of(1))))assertEquals(409,assertThrows(DomainException.class,()->platform.idempotent(actor,TENANT,"/mapping-conflict",UUID.randomUUID().toString(),"conflict",access.permissionVersion(),()->repository.saveMapping(access,parsed.source(),request))).status);
        assertEquals(version,number(source(parsed),"version"));assertEquals(0,count("mop_asset_import_mapping","source_id",parsed.source()));
        Accepted reparsed=parse(parsed);assertNull(source(reparsed).get("parsed_artifact"));assertNull(source(reparsed).get("mapping"));assertTrue(repository.processNext("import-test-reparse",store,parser(),Set.of(reparsed.source())));assertEquals(2,count("mop_asset_import_artifact","source_id",reparsed.source()));
    }
    @Test void upstreamOutageAfterFiveFailuresStillRetriesSameJobAndNaturallyRecovers()throws Exception{
        Accepted accepted=accept(UUID.randomUUID().toString());AssetImportObjectStore offline=new AssetImportObjectStore(){public boolean ready(){return false;}public void storeImmutable(String key,byte[] original,ImportFormat format,String hash){throw new DomainException(503,"UPSTREAM_UNAVAILABLE","MOP-FAKE outage");}public byte[] read(String key,long size,String hash){throw new AssertionError();}};
        for(int attempt=1;attempt<=6;attempt++){assertTrue(repository.processNext("import-test-offline",offline,neverParser(),Set.of(accepted.source())));assertEquals("PENDING",source(accepted).get("storage_status"));assertEquals("PENDING",operation(accepted).get("status"));assertEquals("RETRY_WAIT",operation(accepted).get("step"));assertEquals(1,update("UPDATE public.mop_asset_import_job SET next_at=clock_timestamp()-interval '1 second' WHERE source_id=?::uuid AND kind='STORE_RAW'",accepted.source()));}
        Store recovered=new Store();ready(accepted,recovered);assertEquals(1,recovered.creates.get());assertEquals("SUCCEEDED",operation(accepted).get("status"));assertEquals(1,count("mop_asset_import_job","source_id",accepted.source()));
    }
    @Test void successfulPutWithoutActualReadbackCannotBecomeReadyAndIntegrityConflictIsTerminal(){
        Accepted accepted=accept(UUID.randomUUID().toString());AtomicInteger puts=new AtomicInteger();AssetImportObjectStore invalid=new AssetImportObjectStore(){public boolean ready(){return true;}public void storeImmutable(String key,byte[] original,ImportFormat format,String hash){puts.incrementAndGet();}public byte[] read(String key,long size,String hash){return new byte[]{1};}};
        assertTrue(repository.processNext("import-test-false-store",invalid,neverParser(),Set.of(accepted.source())));assertEquals(1,puts.get());assertEquals("FAILED",source(accepted).get("storage_status"));assertEquals("STORAGE_CONFLICT",operation(accepted).get("step"));assertFalse(repository.processNext("import-test-no-retry",invalid,neverParser(),Set.of(accepted.source())));
    }
    @Test void parserFormatResourceRejectionIsTerminalButInternalFailureRetries()throws Exception{
        for(ParseFailure.Code code:List.of(ParseFailure.Code.RESOURCE_LIMIT,ParseFailure.Code.FORMULA_NOT_ALLOWED,ParseFailure.Code.INTERNAL_PARSER_FAILURE)){
            Accepted accepted=accept(UUID.randomUUID().toString());Store store=new Store();ready(accepted,store);Accepted parse=parse(accepted);AssetImportParser reject=(path,selection)->{throw new ParseFailure(code);};assertTrue(repository.processNext("import-test-parser-reject",store,reject,Set.of(parse.source())));
            assertEquals(code==ParseFailure.Code.INTERNAL_PARSER_FAILURE?"PENDING":"FAILED",source(parse).get("parse_status"));assertEquals(code==ParseFailure.Code.INTERNAL_PARSER_FAILURE?"RETRY_WAIT":"PARSE_INVALID",operation(parse).get("step"));assertNull(source(parse).get("parsed_artifact"));assertEquals(0,count("mop_asset_import_artifact","source_id",parse.source()));
        }
    }
    @Test void currentPermissionVersionIsRecheckedForWritesReplayAndWorkerBeforeAnyExternalIo()throws Exception{
        String key=UUID.randomUUID().toString();Accepted accepted=accept(key);var stale=access();Store store=new Store();
        try{assertEquals(1,update("UPDATE public.mop_membership SET permission_version=permission_version+1 WHERE tenant_id=?::uuid AND user_id=?::uuid",TENANT,actor));
            assertEquals(401,assertThrows(DomainException.class,()->repository.source(stale,accepted.source())).status);assertEquals(401,assertThrows(DomainException.class,()->platform.idempotent(actor,TENANT,"/api/v1/assets/import-sources",key,PostgresAssetImportRepository.sha(RAW),stale.permissionVersion(),()->repository.acceptSource(stale,"MOP-FAKE.csv",ImportFormat.CSV,RAW))).status);
            assertTrue(repository.processNext("import-test-revoked",store,neverParser(),Set.of(accepted.source())));assertTrue(store.originals.isEmpty());assertEquals("FAILED",source(accepted).get("storage_status"));assertEquals("AUTHORIZATION_REVOKED",operation(accepted).get("step"));
        }finally{update("UPDATE public.mop_membership SET permission_version=permission_version+1 WHERE tenant_id=?::uuid AND user_id=?::uuid",TENANT,actor);}
    }
    static AssetImportObjectStore pausedStore(Accepted accepted,Store delegate,CountDownLatch entered,CountDownLatch release){return new AssetImportObjectStore(){public boolean ready(){return true;}public byte[] read(String key,long size,String hash){return delegate.read(key,size,hash);}public void storeImmutable(String key,byte[] bytes,ImportFormat format,String hash){assertTrue(key.endsWith(accepted.source()));entered.countDown();try{if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("MOP-FAKE pause timeout");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("MOP-FAKE interruption");}delegate.storeImmutable(key,bytes,format,hash);}};}
    @Test void revocationDuringObjectIoCannotCommitReady()throws Exception{
        Accepted accepted=accept(UUID.randomUUID().toString());Store store=new Store();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService threads=Executors.newSingleThreadExecutor();Future<Boolean> old=threads.submit(()->repository.processNext("import-test-revoke-io",pausedStore(accepted,store,entered,release),neverParser(),Set.of(accepted.source())));
        try{assertTrue(entered.await(10,TimeUnit.SECONDS));update("UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 WHERE tenant_id=?::uuid AND user_id=?::uuid",TENANT,actor);release.countDown();assertTrue(old.get(20,TimeUnit.SECONDS));}
        finally{release.countDown();threads.shutdownNow();update("UPDATE public.mop_membership SET active=true,permission_version=permission_version+1 WHERE tenant_id=?::uuid AND user_id=?::uuid",TENANT,actor);}
        assertEquals("FAILED",source(accepted).get("storage_status"));assertEquals("AUTHORIZATION_REVOKED",operation(accepted).get("step"));
    }
    @Test void permissionChangeDuringRawReadPreventsParserExecution()throws Exception{
        Accepted accepted=accept(UUID.randomUUID().toString());Store store=new Store();ready(accepted,store);Accepted parse=parse(accepted);AtomicInteger calls=new AtomicInteger();
        AssetImportObjectStore revokeOnRead=new AssetImportObjectStore(){public boolean ready(){return true;}public void storeImmutable(String key,byte[] bytes,ImportFormat format,String hash){throw new AssertionError();}public byte[] read(String key,long size,String hash){try{update("UPDATE public.mop_membership SET permission_version=permission_version+1 WHERE tenant_id=?::uuid AND user_id=?::uuid",TENANT,actor);}catch(SQLException failure){throw new IllegalStateException("MOP-FAKE revocation failed");}return store.read(key,size,hash);}};
        try{assertTrue(repository.processNext("import-test-revoke-read",revokeOnRead,(path,selection)->{calls.incrementAndGet();return rawParsed();},Set.of(parse.source())));assertEquals(0,calls.get());assertEquals("READY",source(parse).get("storage_status"));assertEquals("FAILED",source(parse).get("parse_status"));assertEquals("AUTHORIZATION_REVOKED",operation(parse).get("step"));assertEquals(0,count("mop_asset_import_artifact","source_id",parse.source()));}
        finally{update("UPDATE public.mop_membership SET permission_version=permission_version+1 WHERE tenant_id=?::uuid AND user_id=?::uuid",TENANT,actor);}
    }
    static AssetImportParser pausedParser(CountDownLatch entered,CountDownLatch release){return (path,selection)->{entered.countDown();try{if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("MOP-FAKE parser pause timeout");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("MOP-FAKE parser interruption");}return rawParsed();};}
    @Test void revocationDuringParseCannotCommitArtifactOrMapping()throws Exception{
        Accepted accepted=accept(UUID.randomUUID().toString());Store store=new Store();ready(accepted,store);Accepted parse=parse(accepted);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService threads=Executors.newSingleThreadExecutor();Future<Boolean> old=threads.submit(()->repository.processNext("import-test-revoke-parse",store,pausedParser(entered,release),Set.of(parse.source())));
        try{assertTrue(entered.await(10,TimeUnit.SECONDS));update("UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 WHERE tenant_id=?::uuid AND user_id=?::uuid",TENANT,actor);release.countDown();assertTrue(old.get(20,TimeUnit.SECONDS));}
        finally{release.countDown();threads.shutdownNow();update("UPDATE public.mop_membership SET active=true,permission_version=permission_version+1 WHERE tenant_id=?::uuid AND user_id=?::uuid",TENANT,actor);}
        assertEquals("READY",source(parse).get("storage_status"));assertEquals("FAILED",source(parse).get("parse_status"));assertEquals("AUTHORIZATION_REVOKED",operation(parse).get("step"));assertNull(source(parse).get("parsed_artifact"));assertEquals(0,count("mop_asset_import_artifact","source_id",parse.source()));
    }
    @Test void expiredLeaseIsReclaimedAndOldTokenCannotOverwriteCurrentCompletion()throws Exception{
        Accepted accepted=accept(UUID.randomUUID().toString());Store store=new Store();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService threads=Executors.newSingleThreadExecutor();Future<Boolean> old=threads.submit(()->repository.processNext("import-test-old",pausedStore(accepted,store,entered,release),neverParser(),Set.of(accepted.source())));
        try{assertTrue(entered.await(10,TimeUnit.SECONDS));update("UPDATE public.mop_asset_import_job SET lease_until=clock_timestamp()-interval '1 second' WHERE source_id=?::uuid",accepted.source());var restartedPlatform=new PostgresPlatformRepository(pool,mapper);var restarted=new PostgresAssetImportRepository(pool,mapper,restartedPlatform);assertTrue(restarted.processNext("import-test-new",store,neverParser(),Set.of(accepted.source())));long version=number(source(accepted),"version");release.countDown();assertTrue(old.get(20,TimeUnit.SECONDS));assertEquals(version,number(source(accepted),"version"));assertEquals("READY",source(accepted).get("storage_status"));assertEquals("SUCCEEDED",operation(accepted).get("status"));assertEquals(1,store.creates.get());
            try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT fencing_token,attempts FROM public.mop_asset_import_job WHERE source_id=?::uuid")){statement.setString(1,accepted.source());try(ResultSet rows=statement.executeQuery()){assertTrue(rows.next());assertEquals(2,rows.getLong(1));assertEquals(2,rows.getInt(2));}}
        }finally{release.countDown();threads.shutdownNow();}
    }
    @Test void expiredParseLeaseRejectsOldArtifactAfterCurrentWorkerCompletes()throws Exception{
        Accepted accepted=accept(UUID.randomUUID().toString());Store store=new Store();ready(accepted,store);Accepted parse=parse(accepted);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService threads=Executors.newSingleThreadExecutor();Future<Boolean> old=threads.submit(()->repository.processNext("import-test-old-parse",store,pausedParser(entered,release),Set.of(parse.source())));
        try{assertTrue(entered.await(10,TimeUnit.SECONDS));update("UPDATE public.mop_asset_import_job SET lease_until=clock_timestamp()-interval '1 second' WHERE source_id=?::uuid AND kind='PARSE'",parse.source());assertTrue(repository.processNext("import-test-new-parse",store,parser(),Set.of(parse.source())));Map<String,Object> current=source(parse);String artifact=(String)artifact(parse).get("id");release.countDown();assertTrue(old.get(20,TimeUnit.SECONDS));assertEquals(current,source(parse));assertEquals(artifact,operation(parse).get("result_artifact_id"));assertEquals(1,count("mop_asset_import_artifact","source_id",parse.source()));}
        finally{release.countDown();threads.shutdownNow();}
    }
    @Test void interruptedParserResultRemainsLeasedWithoutSuccessAuditAndRecoversUnderNewLease()throws Exception{
        Accepted accepted=accept(UUID.randomUUID().toString());Store store=new Store();ready(accepted,store);Accepted parse=parse(accepted);ExecutorService threads=Executors.newSingleThreadExecutor();
        try{Future<Boolean> stopped=threads.submit(()->repository.processNext("import-test-interrupted",store,(path,selection)->{Thread.currentThread().interrupt();return rawParsed();},Set.of(parse.source())));assertTrue(stopped.get(20,TimeUnit.SECONDS));}
        finally{threads.shutdownNow();}
        assertEquals("PENDING",source(parse).get("parse_status"));assertEquals("RUNNING",operation(parse).get("status"));assertNull(source(parse).get("parsed_artifact"));assertEquals(0,count("mop_asset_import_artifact","source_id",parse.source()));
        try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT count(*) FROM public.mop_audit WHERE tenant_id=?::uuid AND payload->>'resource_id'=? AND payload->>'action'='asset.import.worker.completed' AND payload->>'reason' IN ('PARSED','SELECTION_REQUIRED')")){statement.setString(1,TENANT);statement.setString(2,parse.source());try(ResultSet rows=statement.executeQuery()){rows.next();assertEquals(0,rows.getLong(1));}}
        update("UPDATE public.mop_asset_import_job SET lease_until=clock_timestamp()-interval '1 second' WHERE source_id=?::uuid AND kind='PARSE'",parse.source());assertTrue(repository.processNext("import-test-live-after-stop",store,parser(),Set.of(parse.source())));assertEquals("PARSED",source(parse).get("parse_status"));assertEquals("SUCCEEDED",operation(parse).get("status"));assertEquals(1,count("mop_asset_import_artifact","source_id",parse.source()));
    }
    @Test void commandAndJobScopesAreBoundedAndRestoredOnNestedRollback()throws Exception{
        assertThrows(IllegalStateException.class,platform::importCommandConnection);var access=access();
        Object result=platform.idempotent(actor,TENANT,"/import-scope-test",UUID.randomUUID().toString(),"scope",access.permissionVersion(),()->{Connection active=platform.importCommandConnection();assertSame(active,platform.importJobTransaction(platform::importCommandConnection));assertThrows(IllegalStateException.class,()->platform.importJobTransaction(()->{assertSame(active,platform.importCommandConnection());throw new IllegalStateException("MOP-FAKE nested scope abort");}));assertSame(active,platform.importCommandConnection());return Map.of("scope_checked",true);});
        assertEquals(true,mapper.valueToTree(result).path("scope_checked").asBoolean());assertThrows(IllegalStateException.class,platform::importCommandConnection);
        assertThrows(IllegalStateException.class,()->platform.importJobTransaction(()->{assertNotNull(platform.importCommandConnection());throw new IllegalStateException("MOP-FAKE outer scope rollback");}));assertThrows(IllegalStateException.class,platform::importCommandConnection);
        var unprotected=PostgresPlatformRepository.class.getDeclaredMethod("inTransaction",java.util.function.Supplier.class);unprotected.setAccessible(true);java.lang.reflect.InvocationTargetException failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->unprotected.invoke(platform,(java.util.function.Supplier<Connection>)platform::importCommandConnection));assertInstanceOf(IllegalStateException.class,failure.getCause());assertThrows(IllegalStateException.class,platform::importCommandConnection);
    }
    @Test void databaseRejectsWrongRawDigestSizeOrShadowStoreOperationAndCrossArtifactPointers()throws Exception{
        var access=access();
        for(byte[] wrong:List.of(new byte[]{1},Arrays.copyOf(RAW,RAW.length))){
            byte[] invalid=wrong.clone();if(invalid.length==RAW.length)invalid[0]^=1;
            SQLException failure=assertThrows(SQLException.class,()->{try(Connection connection=pool.getConnection()){
                connection.setAutoCommit(false);try{
                    String source=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
                    try(PreparedStatement sourceInsert=connection.prepareStatement("INSERT INTO public.mop_asset_import_source(id,tenant_id,created_by,original_name,declared_format,size_bytes,sha256,object_key,permission_version,store_operation_id) VALUES (?::uuid,?::uuid,?::uuid,'MOP-FAKE-constraint.csv','CSV',?,?,?,?,?::uuid)")){sourceInsert.setString(1,source);sourceInsert.setString(2,TENANT);sourceInsert.setString(3,actor);sourceInsert.setInt(4,RAW.length);sourceInsert.setString(5,PostgresAssetImportRepository.sha(RAW));sourceInsert.setString(6,"asset-import-sources/"+TENANT+"/"+source);sourceInsert.setLong(7,access.permissionVersion());sourceInsert.setString(8,operation);sourceInsert.executeUpdate();}
                    try(PreparedStatement operationInsert=connection.prepareStatement("INSERT INTO public.mop_asset_import_operation(id,tenant_id,source_id,created_by,permission_version,kind) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,'STORE_RAW')")){operationInsert.setString(1,operation);operationInsert.setString(2,TENANT);operationInsert.setString(3,source);operationInsert.setString(4,actor);operationInsert.setLong(5,access.permissionVersion());operationInsert.executeUpdate();}
                    try(PreparedStatement jobInsert=connection.prepareStatement("INSERT INTO public.mop_asset_import_job(id,tenant_id,source_id,operation_id,kind,content) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,'STORE_RAW',?)")){jobInsert.setString(1,UUID.randomUUID().toString());jobInsert.setString(2,TENANT);jobInsert.setString(3,source);jobInsert.setString(4,operation);jobInsert.setBytes(5,invalid);jobInsert.executeUpdate();}
                    connection.commit();
                }finally{connection.rollback();}
            }});assertEquals("23503",failure.getSQLState());
        }
        Accepted accepted=accept(UUID.randomUUID().toString());String shadow=UUID.randomUUID().toString();
        try(Connection connection=pool.getConnection()){
            connection.setAutoCommit(false);try{
                try(PreparedStatement operationInsert=connection.prepareStatement("INSERT INTO public.mop_asset_import_operation(id,tenant_id,source_id,created_by,permission_version,kind) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,'STORE_RAW')")){operationInsert.setString(1,shadow);operationInsert.setString(2,TENANT);operationInsert.setString(3,accepted.source());operationInsert.setString(4,actor);operationInsert.setLong(5,access.permissionVersion());operationInsert.executeUpdate();}
                SQLException failure=assertThrows(SQLException.class,()->{try(PreparedStatement jobInsert=connection.prepareStatement("INSERT INTO public.mop_asset_import_job(id,tenant_id,source_id,operation_id,kind,content) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,'STORE_RAW',?)")){jobInsert.setString(1,UUID.randomUUID().toString());jobInsert.setString(2,TENANT);jobInsert.setString(3,accepted.source());jobInsert.setString(4,shadow);jobInsert.setBytes(5,RAW);jobInsert.executeUpdate();}});assertEquals("23503",failure.getSQLState());
            }finally{connection.rollback();}
        }
        Store store=new Store();ready(accepted,store);Accepted first=parse(accepted);assertTrue(repository.processNext("import-test-fk-first",store,parser(),Set.of(first.source())));saveMapping(first);Accepted second=accept(UUID.randomUUID().toString());ready(second,store);second=parse(second);assertTrue(repository.processNext("import-test-fk-second",store,parser(),Set.of(second.source())));Map<?,?> secondArtifact=artifact(second);Map<?,?> secondMapping=(Map<?,?>)saveMapping(second).get("mapping");
        for(Object[] mutation:List.of(new Object[]{"UPDATE public.mop_asset_import_source SET parse_operation_id=?::uuid WHERE id=?::uuid",second.operation()},new Object[]{"UPDATE public.mop_asset_import_source SET latest_artifact_id=?::uuid WHERE id=?::uuid",secondArtifact.get("id")},new Object[]{"UPDATE public.mop_asset_import_source SET latest_mapping_id=?::uuid WHERE id=?::uuid",secondMapping.get("id")}))assertEquals("23503",assertThrows(SQLException.class,()->update((String)mutation[0],mutation[1],first.source())).getSQLState());
    }
    @Test void runtimeCannotRewriteOriginalBytesOwnerHashArtifactMappingOrDeleteHistory()throws Exception{
        Accepted accepted=accept(UUID.randomUUID().toString());Store store=new Store();ready(accepted,store);Accepted parse=parse(accepted);assertTrue(repository.processNext("import-test-immutability",store,parser(),Set.of(parse.source())));saveMapping(parse);
        for(String sql:List.of("UPDATE public.mop_asset_import_source SET sha256=sha256 WHERE id=?::uuid","UPDATE public.mop_asset_import_source SET created_by=created_by WHERE id=?::uuid","UPDATE public.mop_asset_import_source SET object_key=object_key WHERE id=?::uuid","UPDATE public.mop_asset_import_job SET content=content WHERE source_id=?::uuid","UPDATE public.mop_asset_import_artifact SET artifact_json=artifact_json WHERE source_id=?::uuid","UPDATE public.mop_asset_import_mapping SET mapped_raw_rows=mapped_raw_rows WHERE source_id=?::uuid","DELETE FROM public.mop_asset_import_source WHERE id=?::uuid","DELETE FROM public.mop_asset_import_mapping WHERE source_id=?::uuid")){
            SQLException failure=assertThrows(SQLException.class,()->update(sql,parse.source()));assertEquals("42501",failure.getSQLState());
        }
        assertTrue(repository.ready());assertEquals(1,count("mop_asset_import_artifact","source_id",parse.source()));assertEquals(1,count("mop_asset_import_mapping","source_id",parse.source()));
    }
}
