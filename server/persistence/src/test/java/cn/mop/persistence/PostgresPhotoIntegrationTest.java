package cn.mop.persistence;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Designated PostgreSQL transaction tests. Store doubles inject failures/concurrency only;
 * this class is not MinIO integration evidence. Requires an explicitly reviewed/applied V5.
 * Every injected store claims only this case’s fresh explicit file UUID through a package-local SQL scope.
 * No test migrates schema, consumes another job, creates local components or substitutes for PostgreSQL.
 */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresPhotoIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    static final byte[] PNG=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jR4kAAAAASUVORK5CYII=");
    static HikariDataSource pool;static ObjectMapper mapper;static PostgresPlatformRepository repository;
    static String actor,other,otherTenant,admin,sha;
    @BeforeAll static void connect()throws Exception{
        String url=required("SPRING_DATASOURCE_URL");assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",required("SPRING_DATASOURCE_USERNAME"));
        HikariConfig config=new HikariConfig();config.setJdbcUrl(url);config.setUsername(required("SPRING_DATASOURCE_USERNAME"));config.setPassword(required("SPRING_DATASOURCE_PASSWORD"));config.setMaximumPoolSize(6);config.setConnectionTimeout(5000);pool=new HikariDataSource(config);
        mapper=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);repository=new PostgresPlatformRepository(pool,mapper);
        assertTrue(repository.dependencyReady(),"Designated restricted PostgreSQL is required");assertTrue(repository.fileWorkerReady(),"Explicitly review/apply V5 before these tests; test startup never migrates it");
        actor=user("租户 A 只读用户（测试身份）");other=user("租户 A 管理员（测试身份）");otherTenant=user("租户 B 只读用户（测试身份）");admin=user("平台管理员（测试身份）");sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PNG));
    }
    @AfterAll static void close(){if(pool!=null)pool.close();}
    static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Missing private remote setting "+key);return value;}
    static String user(String name){return repository.users().stream().filter(u->name.equals(u.displayName())).findFirst().orElseThrow().id();}
    record Accepted(String operation,String file){}
    static Accepted upload(String uploader,String key){
        return uploadInTenant(TENANT,uploader,key);
    }
    static Accepted uploadInTenant(String tenant,String uploader,String key){
        Object response=repository.idempotent(uploader,tenant,"POST /integration/files",key,sha,()->{
            Map<String,Object> accepted=repository.acceptPhoto(tenant,uploader,new PhotoUpload("MOP-FAKE-"+key+".png","image/png",sha,PNG));
            repository.audit(uploader,tenant,"integration.file.accept","operation",accepted.get("operation_id").toString(),"SUCCEEDED","MOP_FAKE",key);return accepted;
        });String operation=mapper.valueToTree(response).path("operation_id").asText();String ref=repository.operation(tenant,uploader,operation).orElseThrow().get("result_ref").toString();return new Accepted(operation,ref.substring(ref.lastIndexOf('/')+1));
    }
    static Accepted ready(){Accepted accepted=upload(actor,UUID.randomUUID().toString());drainUntilReady(accepted,new ImmutableTestStore());return accepted;}
    static void drainUntilReady(Accepted accepted,PhotoObjectStore store){for(int i=0;i<100&&!"READY".equals(repository.file(TENANT,actor,accepted.file()).orElseThrow().get("upload_status"));i++)assertTrue(repository.processNextPhoto("integration-drain",store,Set.of(accepted.file())));assertEquals("READY",repository.file(TENANT,actor,accepted.file()).orElseThrow().get("upload_status"));}
    static String ticket(CustomerReport report){
        Object response=repository.idempotent(actor,TENANT,"POST /integration/photo-tickets",UUID.randomUUID().toString(),UUID.randomUUID().toString(),()->{
            CustomerReportRegistration registered=repository.registerCustomerReport(TENANT,actor,report);repository.audit(actor,TENANT,registered.created()?"integration.ticket.register":"integration.ticket.attach","ticket",registered.ticketId(),"SUCCEEDED","MOP_FAKE",UUID.randomUUID().toString());return Map.of("ticket_id",registered.ticketId());
        });return mapper.valueToTree(response).path("ticket_id").asText();
    }
    static CustomerReport report(String description,String asset,List<String> photos){return new CustomerReport(null,description,"网络不通","individual","虚构测试联系人","0000","urgent",asset,photos);}
    static Map<String,Object> append(String id,long version,List<String> photos){return (Map<String,Object>)repository.idempotent(actor,TENANT,"POST /integration/photo-tickets/"+id+"/attachments",UUID.randomUUID().toString(),UUID.randomUUID().toString(),()->{
        Map<String,Object> updated=repository.attachPhotos(TENANT,actor,id,version,photos);repository.audit(actor,TENANT,"integration.ticket.attach","ticket",id,"SUCCEEDED","MOP_FAKE",UUID.randomUUID().toString());return updated;
    });}
    static void update(String sql,String... parameters)throws Exception{try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement(sql)){for(int i=0;i<parameters.length;i++)statement.setString(i+1,parameters[i]);assertEquals(1,statement.executeUpdate());}}
    static Map<String,Object> operation(Accepted a){return repository.operation(TENANT,actor,a.operation()).orElseThrow();}
    static long count(String table,String column,String id)throws Exception{try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT count(*) FROM public."+table+" WHERE "+column+"::text=?")){statement.setString(1,id);try(ResultSet rows=statement.executeQuery()){rows.next();return rows.getLong(1);}}}
    static String token(Map<String,Object> grant){return grant.get("content_url").toString().split("grant=",2)[1];}
    static final class ImmutableTestStore implements PhotoObjectStore {
        final ConcurrentMap<String,byte[]> originals=new ConcurrentHashMap<>();final AtomicInteger creates=new AtomicInteger();
        public boolean ready(){return true;}
        public void storeImmutable(String key,byte[] content,String media,String hash){byte[] existing=originals.putIfAbsent(key,content.clone());if(existing==null)creates.incrementAndGet();else if(!Arrays.equals(existing,content))throw new DomainException(409,"FILE_INTEGRITY_CONFLICT","Injected immutable conflict");}
        public byte[] read(String key,long size,String hash){byte[] result=originals.get(key);if(result==null)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","Injected absent object");PhotoRules.validateStoredContent(result,hash);assertEquals(size,result.length);return result.clone();}
    }
    @Test void acceptanceBytesOperationAuditAndReplaySurviveRepositoryRestart()throws Exception{
        String key=UUID.randomUUID().toString();Accepted a=upload(actor,key);assertEquals(a,upload(actor,key));assertEquals("PENDING",operation(a).get("status"));assertEquals("QUEUED",operation(a).get("step"));
        PostgresPlatformRepository restarted=new PostgresPlatformRepository(pool,mapper);Map<String,Object> metadata=restarted.file(TENANT,actor,a.file()).orElseThrow();assertEquals("PENDING",metadata.get("upload_status"));assertFalse(metadata.containsKey("object_key"));assertFalse(metadata.containsKey("content"));assertFalse(metadata.containsKey("bucket"));assertNull(metadata.get("retention_until"));
        assertEquals(1,count("mop_file","id",a.file()));assertEquals(1,count("mop_photo_job","file_id",a.file()));assertEquals(1,count("mop_operation","id",a.operation()));
        try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT j.content,f.size_bytes,f.sha256 FROM public.mop_photo_job j JOIN public.mop_file f ON f.id=j.file_id AND f.tenant_id=j.tenant_id WHERE f.id::text=?")){statement.setString(1,a.file());try(ResultSet rows=statement.executeQuery()){assertTrue(rows.next());assertArrayEquals(PNG,rows.getBytes(1));assertEquals(PNG.length,rows.getLong(2));assertEquals(sha,rows.getString(3));}}
        assertTrue(repository.audits(TENANT).stream().anyMatch(audit->key.equals(audit.get("trace_id"))));drainUntilReady(a,new ImmutableTestStore());assertEquals("SUCCEEDED",operation(a).get("status"));assertEquals("STORED",operation(a).get("step"));assertNull(operation(a).get("error"));
    }
    @Test void scopedInjectedStoreNeverClaimsAnotherPendingFileEvenIfItWasCreatedEarlier()throws Exception{
        Accepted unrelated=upload(actor,UUID.randomUUID().toString()),target=upload(actor,UUID.randomUUID().toString());ImmutableTestStore store=new ImmutableTestStore();drainUntilReady(target,store);
        assertEquals("PENDING",operation(unrelated).get("status"));assertEquals("QUEUED",operation(unrelated).get("step"));assertEquals(1,countJobState(unrelated.file(),"PENDING"));assertEquals("PENDING",repository.file(TENANT,actor,unrelated.file()).orElseThrow().get("upload_status"));assertFalse(store.originals.containsKey("photos/"+TENANT+"/"+unrelated.file()));
        // This second file was also created by this case; consume it only through its own explicit scope.
        drainUntilReady(unrelated,store);
    }
    @Test void failureAfterAcceptanceRollsBackPrivateBytesOperationAuditAndReplay()throws Exception{
        String key=UUID.randomUUID().toString();String[] attempted=new String[2];
        assertThrows(IllegalStateException.class,()->repository.idempotent(actor,TENANT,"POST /integration/files",key,sha,()->{
            Map<String,Object> accepted=repository.acceptPhoto(TENANT,actor,new PhotoUpload("MOP-FAKE-rollback.png","image/png",sha,PNG));attempted[0]=accepted.get("operation_id").toString();String ref=repository.operation(TENANT,actor,attempted[0]).orElseThrow().get("result_ref").toString();attempted[1]=ref.substring(ref.lastIndexOf('/')+1);repository.audit(actor,TENANT,"integration.file.rollback","file",attempted[1],"SUCCEEDED","MOP_FAKE",key);throw new IllegalStateException("intentional rollback");
        }));assertEquals(0,count("mop_file","id",attempted[1]));assertEquals(0,count("mop_photo_job","file_id",attempted[1]));assertEquals(0,count("mop_operation","id",attempted[0]));assertTrue(repository.audits(TENANT).stream().noneMatch(audit->key.equals(audit.get("trace_id"))));Accepted retry=upload(actor,key);assertNotEquals(attempted[0],retry.operation());drainUntilReady(retry,new ImmutableTestStore());
    }
    @Test void scopedMetadataAndFifteenMinuteGrantRequireTheSameCurrentActorPermissionAndTenant()throws Exception{
        Accepted a=ready();assertTrue(repository.file(TENANT,other,a.file()).isEmpty());assertTrue(repository.operation(TENANT,other,a.operation()).isEmpty());assertTrue(repository.file(OTHER,otherTenant,a.file()).isEmpty());assertTrue(repository.file(TENANT,admin,a.file()).isEmpty());
        Map<String,Object> grant=repository.contentGrant(TENANT,actor,a.file());String token=token(grant);Map<String,Object> descriptor=repository.photoContent(TENANT,actor,a.file(),token);assertEquals(sha,descriptor.get("sha256"));assertEquals(PNG.length,((Number)descriptor.get("byte_size")).longValue());
        assertEquals(404,assertThrows(DomainException.class,()->repository.photoContent(TENANT,admin,a.file(),token)).status);assertEquals(404,assertThrows(DomainException.class,()->repository.photoContent(OTHER,otherTenant,a.file(),token)).status);
        try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT expires_at-created_at BETWEEN interval '15 minutes' AND interval '15 minutes 1 second' FROM public.mop_file_grant WHERE id::text=?")){statement.setString(1,token);try(ResultSet rows=statement.executeQuery()){assertTrue(rows.next());assertTrue(rows.getBoolean(1));}}
        update("UPDATE public.mop_membership SET permission_version=permission_version+1 WHERE tenant_id::text=? AND user_id::text=?",TENANT,actor);assertEquals(404,assertThrows(DomainException.class,()->repository.photoContent(TENANT,actor,a.file(),token)).status);assertDoesNotThrow(()->repository.photoContent(TENANT,actor,a.file(),token(repository.contentGrant(TENANT,actor,a.file()))));
        String expired=UUID.randomUUID().toString();try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_file_grant(id,tenant_id,file_id,actor_id,permission_version,created_at,expires_at) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,clock_timestamp()-interval '16 minutes',clock_timestamp()-interval '1 second')")){statement.setString(1,expired);statement.setString(2,TENANT);statement.setString(3,a.file());statement.setString(4,actor);statement.setLong(5,repository.membership(actor,TENANT).orElseThrow().permissionVersion());assertEquals(1,statement.executeUpdate());}assertEquals(404,assertThrows(DomainException.class,()->repository.photoContent(TENANT,actor,a.file(),expired)).status);
    }
    @Test void initialAndAddedPhotosShareTheTotalLimitAndFailureRollsBackBindingVersionHistoryAndAudit()throws Exception{
        Accepted a=ready(),b=ready(),c=ready(),extra=ready();String id=ticket(report("MOP-FAKE three-photo guard",null,List.of(a.file())));Map<String,Object> updated=append(id,1,List.of(b.file(),c.file()));assertEquals(2L,updated.get("version"));assertEquals(3,((List<?>)updated.get("attachments")).size());assertEquals(2,((List<?>)updated.get("history")).size());
        assertEquals(422,assertThrows(DomainException.class,()->append(id,2,List.of(extra.file()))).status);assertNull(repository.file(TENANT,actor,extra.file()).orElseThrow().get("ticket_id"));Map<String,Object> actual=repository.ticket(TENANT,actor,id).orElseThrow();assertEquals(2L,actual.get("version"));assertEquals(2,((List<?>)actual.get("history")).size());assertEquals(3,((List<?>)actual.get("attachments")).size());
        assertEquals(2L,append(id,2,List.of(a.file())).get("version"));assertEquals(409,assertThrows(DomainException.class,()->append(id,1,List.of(a.file()))).status);
    }
    @Test void pendingForeignUploaderAndAlreadyBoundPhotosCannotBeAdded()throws Exception{
        Accepted pending=upload(actor,UUID.randomUUID().toString());String id=ticket(report("MOP-FAKE ownership",null,List.of()));assertEquals(422,assertThrows(DomainException.class,()->append(id,1,List.of(pending.file()))).status);drainUntilReady(pending,new ImmutableTestStore());
        Accepted foreign=upload(other,UUID.randomUUID().toString());ImmutableTestStore store=new ImmutableTestStore();for(int i=0;i<100&&!"READY".equals(repository.file(TENANT,other,foreign.file()).orElseThrow().get("upload_status"));i++)assertTrue(repository.processNextPhoto("integration-foreign",store,Set.of(foreign.file())));assertEquals(404,assertThrows(DomainException.class,()->append(id,1,List.of(foreign.file()))).status);
        append(id,1,List.of(pending.file()));String otherTicket=ticket(report("MOP-FAKE second binding",null,List.of()));assertEquals(409,assertThrows(DomainException.class,()->append(otherTicket,1,List.of(pending.file()))).status);assertEquals(1L,repository.ticket(TENANT,actor,otherTicket).orElseThrow().get("version"));
    }
    @Test void concurrentAppendsWithTheSameExpectedVersionCommitExactlyOneHistory()throws Exception{
        Accepted a=ready(),b=ready();String id=ticket(report("MOP-FAKE concurrent CAS",null,List.of()));ExecutorService threads=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{List<Future<Integer>> results=new ArrayList<>();for(Accepted photo:List.of(a,b))results.add(threads.submit(()->{start.await();try{append(id,1,List.of(photo.file()));return 200;}catch(DomainException error){return error.status;}}));start.countDown();List<Integer> statuses=new ArrayList<>();for(Future<Integer> result:results)statuses.add(result.get(20,TimeUnit.SECONDS));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);Map<String,Object> actual=repository.ticket(TENANT,actor,id).orElseThrow();assertEquals(2L,actual.get("version"));assertEquals(1,((List<?>)actual.get("attachments")).size());assertEquals(2,((List<?>)actual.get("history")).size());}finally{threads.shutdownNow();}
    }
    @Test void internalReadersOnlySeeAnotherReportersPhotoAfterItIsBoundToAnAuthorizedTicket(){
        Accepted photo=ready();String ownerGrant=token(repository.contentGrant(TENANT,actor,photo.file()));
        assertTrue(repository.file(TENANT,actor,photo.file()).isPresent());assertTrue(repository.operation(TENANT,actor,photo.operation()).isPresent());
        for(String reader:List.of(admin,other)){
            assertTrue(repository.file(TENANT,reader,photo.file()).isEmpty());assertTrue(repository.operation(TENANT,reader,photo.operation()).isEmpty());
            assertEquals(404,assertThrows(DomainException.class,()->repository.contentGrant(TENANT,reader,photo.file())).status);
            assertEquals(404,assertThrows(DomainException.class,()->repository.photoContent(TENANT,reader,photo.file(),ownerGrant)).status);
        }
        String id=ticket(report("MOP-FAKE bound visibility "+UUID.randomUUID(),null,List.of(photo.file())));
        assertEquals(id,repository.file(TENANT,admin,photo.file()).orElseThrow().get("ticket_id"));assertTrue(repository.operation(TENANT,admin,photo.operation()).isPresent());
        String internalGrant=token(repository.contentGrant(TENANT,admin,photo.file()));assertEquals(sha,repository.photoContent(TENANT,admin,photo.file(),internalGrant).get("sha256"));
        assertTrue(repository.file(TENANT,other,photo.file()).isEmpty());assertTrue(repository.operation(TENANT,other,photo.operation()).isEmpty());
        assertEquals(404,assertThrows(DomainException.class,()->repository.contentGrant(TENANT,other,photo.file())).status);
        assertEquals(404,assertThrows(DomainException.class,()->repository.photoContent(TENANT,other,photo.file(),ownerGrant)).status);
        assertTrue(repository.file(OTHER,admin,photo.file()).isEmpty());assertTrue(repository.operation(OTHER,admin,photo.operation()).isEmpty());
        assertEquals(404,assertThrows(DomainException.class,()->repository.contentGrant(OTHER,admin,photo.file())).status);
        assertEquals(404,assertThrows(DomainException.class,()->repository.photoContent(OTHER,admin,photo.file(),internalGrant)).status);
    }
    @Test void historyInsertFailureAfterRealBindingRollsBackFileTicketHistoryAuditAndReplay()throws Exception{assertPostWriteRollback("INSERT INTO public.mop_ticket_history");}
    @Test void auditInsertFailureAfterRealBindingRollsBackFileTicketHistoryAuditAndReplay()throws Exception{assertPostWriteRollback("INSERT INTO public.mop_audit");}
    static void assertPostWriteRollback(String failingPrefix)throws Exception{
        Accepted photo=ready();String id=ticket(report("MOP-FAKE post-write rollback "+UUID.randomUUID(),null,List.of()));String key=UUID.randomUUID().toString(),route="POST /integration/photo-tickets/"+id+"/attachments";
        long fileVersion=((Number)repository.file(TENANT,actor,photo.file()).orElseThrow().get("version")).longValue();AtomicInteger injected=new AtomicInteger();
        PostgresPlatformRepository failing=new PostgresPlatformRepository(afterRealInsertFails(failingPrefix,id,photo.file(),injected),mapper);
        assertEquals(503,assertThrows(DomainException.class,()->appendUsing(failing,id,key,route,photo.file())).status);assertEquals(1,injected.get());
        Map<String,Object> file=repository.file(TENANT,actor,photo.file()).orElseThrow();assertNull(file.get("ticket_id"));assertEquals(fileVersion,((Number)file.get("version")).longValue());
        assertTicketProjection(repository.ticket(TENANT,actor,id).orElseThrow(),id,1,Set.of());assertEquals(0,replayCount(route,key));assertEquals(0,traceAuditCount(key));
        assertTicketProjection((Map<String,Object>)appendUsing(repository,id,key,route,photo.file()),id,2,Set.of(photo.file()));assertEquals(1,replayCount(route,key));assertEquals(1,traceAuditCount(key));
    }
    static Object appendUsing(PostgresPlatformRepository source,String id,String key,String route,String file){return source.idempotent(actor,TENANT,route,key,sha,()->{
        Map<String,Object> updated=source.attachPhotos(TENANT,actor,id,1,List.of(file));source.audit(actor,TENANT,"integration.ticket.post_write_rollback","ticket",id,"SUCCEEDED","MOP_FAKE",key);return updated;
    });}
    static long replayCount(String route,String key)throws Exception{try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT count(*) FROM public.mop_idempotency WHERE tenant_id=?::uuid AND actor_id=?::uuid AND route=? AND request_key=?")){statement.setString(1,TENANT);statement.setString(2,actor);statement.setString(3,route);statement.setString(4,key);try(ResultSet rows=statement.executeQuery()){rows.next();return rows.getLong(1);}}}
    static long traceAuditCount(String trace)throws Exception{try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT count(*) FROM public.mop_audit WHERE tenant_id=?::uuid AND payload->>'trace_id'=?")){statement.setString(1,TENANT);statement.setString(2,trace);try(ResultSet rows=statement.executeQuery()){rows.next();return rows.getLong(1);}}}
    // This seam throws only after the target INSERT succeeds on the designated real PG connection.
    static DataSource afterRealInsertFails(String prefix,String ticket,String file,AtomicInteger injected){
        ClassLoader loader=PostgresPhotoIntegrationTest.class.getClassLoader();
        return (DataSource)Proxy.newProxyInstance(loader,new Class<?>[]{DataSource.class},(proxy,method,args)->{
            Object result=jdbcInvoke(pool,method,args);if(!method.getName().equals("getConnection")||!(result instanceof Connection connection))return result;
            return Proxy.newProxyInstance(loader,new Class<?>[]{Connection.class},(connectionProxy,connectionMethod,connectionArgs)->{
                Object value=jdbcInvoke(connection,connectionMethod,connectionArgs);
                if(!connectionMethod.getName().equals("prepareStatement")||!(connectionArgs[0] instanceof String sql)||!sql.startsWith(prefix)||!(value instanceof PreparedStatement statement))return value;
                return Proxy.newProxyInstance(loader,new Class<?>[]{PreparedStatement.class},(statementProxy,statementMethod,statementArgs)->{
                    Object executed=jdbcInvoke(statement,statementMethod,statementArgs);
                    if(statementMethod.getName().equals("executeUpdate")&&injected.compareAndSet(0,1)){
                        try(PreparedStatement observed=connection.prepareStatement("SELECT f.ticket_id::text,t.version,(SELECT count(*) FROM public.mop_ticket_history h WHERE h.tenant_id=t.tenant_id AND h.ticket_id=t.id) FROM public.mop_file f JOIN public.mop_ticket t ON t.tenant_id=f.tenant_id AND t.id=f.ticket_id WHERE f.id=?::uuid AND t.id=?::uuid")){
                            observed.setString(1,file);observed.setString(2,ticket);try(ResultSet rows=observed.executeQuery()){assertTrue(rows.next());assertEquals(ticket,rows.getString(1));assertEquals(2,rows.getLong(2));assertEquals(2,rows.getLong(3));}
                        }
                        throw new SQLException("Injected failure after real PostgreSQL insert","08006");
                    }return executed;
                });
            });
        });
    }
    @Test void restrictedRuntimeCannotRewriteOriginalBytesHashKeyOrDeletePhotoRecords()throws Exception{
        Accepted photo=ready();String grant=token(repository.contentGrant(TENANT,actor,photo.file()));
        assertRuntimeDenied("UPDATE public.mop_photo_job SET content=content WHERE file_id=?::uuid",photo.file());
        assertRuntimeDenied("UPDATE public.mop_file SET sha256=sha256 WHERE id=?::uuid",photo.file());
        assertRuntimeDenied("UPDATE public.mop_file SET object_key=object_key WHERE id=?::uuid",photo.file());
        assertRuntimeDenied("DELETE FROM public.mop_photo_job WHERE file_id=?::uuid",photo.file());
        assertRuntimeDenied("DELETE FROM public.mop_file WHERE id=?::uuid",photo.file());
        assertRuntimeDenied("DELETE FROM public.mop_file_grant WHERE id=?::uuid",grant);
        assertEquals(1,count("mop_file","id",photo.file()));assertEquals(1,count("mop_photo_job","file_id",photo.file()));assertEquals(1,count("mop_file_grant","id",grant));
    }
    static void assertRuntimeDenied(String sql,String id)throws Exception{
        try(Connection connection=pool.getConnection()){
            connection.setAutoCommit(false);try(PreparedStatement statement=connection.prepareStatement(sql)){
                statement.setString(1,id);SQLException failure=assertThrows(SQLException.class,statement::executeUpdate);assertEquals("42501",failure.getSQLState());
            }finally{connection.rollback();}
        }
    }
    @Test void crossTenantReadyPhotoCannotBindInAnInitialReportOrAnAppend()throws Exception{
        Accepted foreign=uploadInTenant(OTHER,otherTenant,UUID.randomUUID().toString());ImmutableTestStore store=new ImmutableTestStore();
        for(int i=0;i<100&&!"READY".equals(repository.file(OTHER,otherTenant,foreign.file()).orElseThrow().get("upload_status"));i++)assertTrue(repository.processNextPhoto("integration-cross-tenant",store,Set.of(foreign.file())));
        assertEquals("READY",repository.file(OTHER,otherTenant,foreign.file()).orElseThrow().get("upload_status"));
        String id=ticket(report("MOP-FAKE cross-tenant append "+UUID.randomUUID(),null,List.of()));assertEquals(404,assertThrows(DomainException.class,()->append(id,1,List.of(foreign.file()))).status);
        String description="MOP-FAKE cross-tenant initial rollback "+UUID.randomUUID();assertEquals(404,assertThrows(DomainException.class,()->ticket(report(description,null,List.of(foreign.file())))).status);
        try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT count(*) FROM public.mop_ticket WHERE tenant_id=?::uuid AND description=?")){statement.setString(1,TENANT);statement.setString(2,description);try(ResultSet rows=statement.executeQuery()){rows.next();assertEquals(0,rows.getLong(1));}}
        assertNull(repository.file(OTHER,otherTenant,foreign.file()).orElseThrow().get("ticket_id"));assertTicketProjection(repository.ticket(TENANT,actor,id).orElseThrow(),id,1,Set.of());
    }
    @Test void twoTicketsRacingForOneReadyOriginalCommitExactlyOneBinding()throws Exception{
        Accepted photo=ready();String first=ticket(report("MOP-FAKE shared-file first "+UUID.randomUUID(),null,List.of())),second=ticket(report("MOP-FAKE shared-file second "+UUID.randomUUID(),null,List.of()));ExecutorService threads=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            List<Future<Integer>> results=new ArrayList<>();for(String id:List.of(first,second))results.add(threads.submit(()->{start.await();try{append(id,1,List.of(photo.file()));return 200;}catch(DomainException failure){assertEquals("FILE_ALREADY_ATTACHED",failure.code);return failure.status;}}));
            start.countDown();List<Integer> statuses=new ArrayList<>();for(Future<Integer> result:results)statuses.add(result.get(20,TimeUnit.SECONDS));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
            String bound=repository.file(TENANT,actor,photo.file()).orElseThrow().get("ticket_id").toString();assertTrue(Set.of(first,second).contains(bound));
            for(String id:List.of(first,second))assertTicketProjection(repository.ticket(TENANT,actor,id).orElseThrow(),id,id.equals(bound)?2:1,id.equals(bound)?Set.of(photo.file()):Set.of());
        }finally{threads.shutdownNow();}
    }
    // Deliberate invalid SQL proves database protection; these rows are always rolled back and never imply client behavior.
    @Test void databaseRejectsInconsistentFileUploadStateStepAndErrorCombinations()throws Exception{
        for(String[] invalid:List.of(new String[]{"SUCCEEDED","QUEUED",null},new String[]{"PENDING","STORED",null},new String[]{"RUNNING","UPLOADING","{}"},new String[]{"FAILED","AUTHORIZATION_REVOKED",null},new String[]{"SUCCEEDED","STORED","{}"},new String[]{"PENDING","RETRY_WAIT",null})){
            try(Connection connection=pool.getConnection()){
                connection.setAutoCommit(false);try{
                    String file=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();insertPrivateFile(connection,file,operation);
                    SQLException failure=assertThrows(SQLException.class,()->insertFileOperation(connection,operation,file,invalid[0],invalid[1],invalid[2]));assertEquals("23514",failure.getSQLState());
                }finally{connection.rollback();}
            }
        }
    }
    @Test void deferredReciprocalForeignKeyRejectsTwoFilesPointingAtEachOthersOperations()throws Exception{
        String firstFile=UUID.randomUUID().toString(),secondFile=UUID.randomUUID().toString(),firstOperation=UUID.randomUUID().toString(),secondOperation=UUID.randomUUID().toString();
        try(Connection connection=pool.getConnection()){
            connection.setAutoCommit(false);try{
                insertPrivateFile(connection,firstFile,secondOperation);insertPrivateFile(connection,secondFile,firstOperation);
                insertFileOperation(connection,firstOperation,firstFile,"PENDING","QUEUED",null);insertFileOperation(connection,secondOperation,secondFile,"PENDING","QUEUED",null);
                SQLException failure=assertThrows(SQLException.class,connection::commit);assertEquals("23503",failure.getSQLState());
            }finally{connection.rollback();}
        }
        assertEquals(0,count("mop_file","id",firstFile));assertEquals(0,count("mop_file","id",secondFile));assertEquals(0,count("mop_operation","id",firstOperation));assertEquals(0,count("mop_operation","id",secondOperation));
    }
    static void insertPrivateFile(Connection connection,String file,String operation)throws Exception{
        try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_file(id,tenant_id,created_by,purpose,original_name,media_type,size_bytes,sha256,object_key,operation_id,permission_version) VALUES (?::uuid,?::uuid,?::uuid,'customer_report_photo','MOP-FAKE-schema-guard.png','image/png',?,?,?,?::uuid,?)")){
            statement.setString(1,file);statement.setString(2,TENANT);statement.setString(3,actor);statement.setInt(4,PNG.length);statement.setString(5,sha);statement.setString(6,"photos/"+TENANT+"/"+file);statement.setString(7,operation);statement.setLong(8,repository.membership(actor,TENANT).orElseThrow().permissionVersion());assertEquals(1,statement.executeUpdate());
        }
    }
    static void insertFileOperation(Connection connection,String operation,String file,String status,String step,String error)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_operation(id,tenant_id,file_id,kind,status,step,result_ref,error) VALUES (?::uuid,?::uuid,?::uuid,'FILE_UPLOAD',?,?,?,?::jsonb)")){
            statement.setString(1,operation);statement.setString(2,TENANT);statement.setString(3,file);statement.setString(4,status);statement.setString(5,step);statement.setString(6,"/api/v1/files/"+file);statement.setString(7,error);assertEquals(1,statement.executeUpdate());
        }
    }
    @Test void ticketDetailKeepsOneSnapshotWhenAnAttachmentCommitsAfterTheTicketRow(){assertTicketReadSnapshot(false);}
    @Test void ticketListKeepsOneSnapshotWhenAnAttachmentCommitsAfterTheTicketRow(){assertTicketReadSnapshot(true);}

    static void assertTicketReadSnapshot(boolean list){
        Accepted original=ready(),added=ready();
        String id=ticket(report("MOP-FAKE snapshot "+UUID.randomUUID(),null,List.of(original.file())));
        String receipt=repository.ticket(TENANT,actor,id).orElseThrow().get("receipt_no").toString();
        AtomicInteger committed=new AtomicInteger();
        PostgresPlatformRepository reader=new PostgresPlatformRepository(afterTicketRowRead(id,()->{
            // The reader is still between SELECTs. This write uses the original pool and commits
            // on another real PostgreSQL connection before the reader projects its attachments.
            assertTicketProjection(append(id,1,List.of(added.file())),id,2,Set.of(original.file(),added.file()));
            committed.incrementAndGet();
        }),mapper);
        Map<String,Object> old=readTicket(reader,id,receipt,list);
        assertEquals(1,committed.get(),"The independent append must commit before projection resumes");
        assertTicketProjection(old,id,1,Set.of(original.file()));
        assertTicketProjection(readTicket(repository,id,receipt,list),id,2,Set.of(original.file(),added.file()));
    }
    static Map<String,Object> readTicket(PostgresPlatformRepository source,String id,String receipt,boolean list){
        if(!list)return source.ticket(TENANT,actor,id).orElseThrow();
        List<Map<String,Object>> found=source.tickets(TENANT,actor,null,null,receipt,null,null,101);
        assertEquals(1,found.size(),"The actual receipt scopes this read to the new test ticket");
        return found.get(0);
    }
    static void assertTicketProjection(Map<String,Object> ticket,String id,long version,Set<String> photoIds){
        JsonNode value=mapper.valueToTree(ticket);assertEquals(id,value.path("id").asText());assertEquals(version,value.path("version").asLong());
        JsonNode history=value.path("history");assertTrue(history.isArray());assertEquals((int)version,history.size());
        for(int index=0;index<history.size();index++)assertEquals(index+1L,history.get(index).path("version").asLong());
        assertEquals(version==1?"REGISTER":"ADD_ATTACHMENTS",history.get(history.size()-1).path("action").asText());
        JsonNode attachments=value.path("attachments");assertTrue(attachments.isArray());assertEquals(photoIds.size(),attachments.size());
        Set<String> actual=new HashSet<>();attachments.forEach(photo->actual.add(photo.path("id").asText()));assertEquals(photoIds,actual);
    }
    // Scheduling hooks only: every SQL statement and ResultSet still belongs to designated PG.
    static DataSource afterTicketRowRead(String ticketId,Runnable afterRow){
        AtomicBoolean delivered=new AtomicBoolean();ClassLoader loader=PostgresPhotoIntegrationTest.class.getClassLoader();
        return (DataSource)Proxy.newProxyInstance(loader,new Class<?>[]{DataSource.class},(proxy,method,args)->{
            Object result=jdbcInvoke(pool,method,args);
            if(!method.getName().equals("getConnection")||!(result instanceof Connection connection))return result;
            return Proxy.newProxyInstance(loader,new Class<?>[]{Connection.class},(connectionProxy,connectionMethod,connectionArgs)->{
                Object value=jdbcInvoke(connection,connectionMethod,connectionArgs);
                if(!connectionMethod.getName().equals("prepareStatement")||!(connectionArgs[0] instanceof String sql)
                    ||!sql.startsWith("SELECT t.* FROM public.mop_ticket t ")||!(value instanceof PreparedStatement statement))return value;
                return Proxy.newProxyInstance(loader,new Class<?>[]{PreparedStatement.class},(statementProxy,statementMethod,statementArgs)->{
                    Object rows=jdbcInvoke(statement,statementMethod,statementArgs);
                    if(!statementMethod.getName().equals("executeQuery")||!(rows instanceof ResultSet resultSet))return rows;
                    return Proxy.newProxyInstance(loader,new Class<?>[]{ResultSet.class},(rowsProxy,rowsMethod,rowsArgs)->{
                        Object next=jdbcInvoke(resultSet,rowsMethod,rowsArgs);
                        if(rowsMethod.getName().equals("next")&&Boolean.TRUE.equals(next)&&ticketId.equals(resultSet.getString("id"))&&delivered.compareAndSet(false,true))afterRow.run();
                        return next;
                    });
                });
            });
        });
    }
    static Object jdbcInvoke(Object target,Method method,Object[] args)throws Throwable{
        try{return method.invoke(target,args);}catch(InvocationTargetException exception){throw exception.getCause();}
    }
    @Test void semanticDedupAppendsPhotosWithoutReplacingTheOriginalDescriptionOrContact(){
        Accepted a=ready(),b=ready();Object created=repository.idempotent(admin,TENANT,"POST /integration/photo-assets",UUID.randomUUID().toString(),UUID.randomUUID().toString(),()->Map.of("id",repository.insert(TENANT,admin,"MOP-FAKE-"+UUID.randomUUID(),"router","192.0.2.45","site-a-main").id()));String asset=mapper.valueToTree(created).path("id").asText();
        String id=ticket(report("MOP-FAKE original preserved",asset,List.of(a.file())));CustomerReport changed=new CustomerReport("Changed title","Changed body","网络不通","company","Changed contact","9999","normal",asset,List.of(b.file()));assertEquals(id,ticket(changed));Map<String,Object> actual=repository.ticket(TENANT,actor,id).orElseThrow();assertEquals("MOP-FAKE original preserved",actual.get("description"));assertEquals("虚构测试联系人",actual.get("contact_name"));assertEquals("0000",actual.get("contact_phone"));assertEquals("urgent",actual.get("urgency"));assertEquals(2,((List<?>)actual.get("attachments")).size());assertEquals(2L,actual.get("version"));
    }
    @Test void transientStorageFailureKeepsTheDurableReceiptAndRetriesTheSameOriginalKey()throws Exception{
        Accepted a=upload(actor,UUID.randomUUID().toString());AtomicInteger calls=new AtomicInteger();ImmutableTestStore delegate=new ImmutableTestStore();PhotoObjectStore flaky=new PhotoObjectStore(){public boolean ready(){return true;}public void storeImmutable(String key,byte[] content,String media,String hash){if(key.endsWith(a.file())&&calls.getAndIncrement()==0)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","Injected unavailable");delegate.storeImmutable(key,content,media,hash);}public byte[] read(String key,long size,String hash){return delegate.read(key,size,hash);}};
        for(int i=0;i<100&&!"RETRY_WAIT".equals(operation(a).get("step"));i++)assertTrue(repository.processNextPhoto("integration-retry",flaky,Set.of(a.file())));assertEquals("PENDING",operation(a).get("status"));assertEquals("RETRY_WAIT",operation(a).get("step"));assertEquals("PENDING",repository.file(TENANT,actor,a.file()).orElseThrow().get("upload_status"));update("UPDATE public.mop_photo_job SET next_at=clock_timestamp()-interval '1 second' WHERE file_id::text=?",a.file());drainUntilReady(a,flaky);assertEquals(2,calls.get());assertArrayEquals(PNG,delegate.read("photos/"+TENANT+"/"+a.file(),PNG.length,sha));
    }
    @Test void objectIntegrityConflictIsTerminalAndNeverBindsOrGrantsTheFile(){
        Accepted a=upload(actor,UUID.randomUUID().toString());ImmutableTestStore delegate=new ImmutableTestStore();PhotoObjectStore conflict=new PhotoObjectStore(){public boolean ready(){return true;}public void storeImmutable(String key,byte[] content,String media,String hash){if(key.endsWith(a.file()))throw new DomainException(409,"FILE_INTEGRITY_CONFLICT","Injected differing original object");delegate.storeImmutable(key,content,media,hash);}public byte[] read(String key,long size,String hash){return delegate.read(key,size,hash);}};
        for(int i=0;i<100&&!"FAILED".equals(operation(a).get("status"));i++)assertTrue(repository.processNextPhoto("integration-integrity",conflict,Set.of(a.file())));assertEquals("FAILED",operation(a).get("status"));assertEquals("STORAGE_CONFLICT",operation(a).get("step"));assertEquals("FAILED",repository.file(TENANT,actor,a.file()).orElseThrow().get("upload_status"));assertEquals(422,assertThrows(DomainException.class,()->repository.contentGrant(TENANT,actor,a.file())).status);
    }
    @Test void concurrentSameUploadKeyCommitsOnlyOneFileJobOperationAndReplay()throws Exception{
        String key=UUID.randomUUID().toString();ExecutorService threads=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{Future<Accepted> first=threads.submit(()->{start.await();return upload(actor,key);}),second=threads.submit(()->{start.await();return upload(actor,key);});start.countDown();Accepted a=first.get(20,TimeUnit.SECONDS),b=second.get(20,TimeUnit.SECONDS);assertEquals(a,b);assertEquals(1,count("mop_file","id",a.file()));assertEquals(1,count("mop_photo_job","file_id",a.file()));assertEquals(1,count("mop_operation","id",a.operation()));drainUntilReady(a,new ImmutableTestStore());}finally{threads.shutdownNow();}
    }
    @Test void revokedUploadIsDeniedBeforeLeaseAndBeforeAnyObjectWrite()throws Exception{
        Accepted a=upload(actor,UUID.randomUUID().toString());ImmutableTestStore store=new ImmutableTestStore();try{
            update("UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 WHERE tenant_id::text=? AND user_id::text=?",TENANT,actor);for(int i=0;i<100&&countJobState(a.file(),"FAILED")==0;i++)assertTrue(repository.processNextPhoto("integration-revoked",store,Set.of(a.file())));assertEquals(1,countJobState(a.file(),"FAILED"));assertFalse(store.originals.containsKey("photos/"+TENANT+"/"+a.file()));
        }finally{update("UPDATE public.mop_membership SET active=true,permission_version=permission_version+1 WHERE tenant_id::text=? AND user_id::text=?",TENANT,actor);}assertEquals("FAILED",operation(a).get("status"));assertEquals("AUTHORIZATION_REVOKED",operation(a).get("step"));
    }
    static long countJobState(String file,String state)throws Exception{try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT count(*) FROM public.mop_photo_job WHERE file_id::text=? AND state=?")){statement.setString(1,file);statement.setString(2,state);try(ResultSet rows=statement.executeQuery()){rows.next();return rows.getLong(1);}}}
    static PhotoObjectStore pausedStore(Accepted target,ImmutableTestStore delegate,CountDownLatch entered,CountDownLatch release){return new PhotoObjectStore(){public boolean ready(){return true;}public byte[] read(String key,long size,String hash){return delegate.read(key,size,hash);}public void storeImmutable(String key,byte[] content,String media,String hash){if(key.endsWith(target.file())){entered.countDown();try{if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("Timed out awaiting test release");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted test store");}}delegate.storeImmutable(key,content,media,hash);}};}
    @Test void revocationDuringExternalIoCannotCommitReadyAndLeavesNoAccessibleGrant()throws Exception{
        Accepted a=upload(actor,UUID.randomUUID().toString());ImmutableTestStore store=new ImmutableTestStore();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService threads=Executors.newSingleThreadExecutor();Future<?> old=threads.submit(()->{PhotoObjectStore paused=pausedStore(a,store,entered,release);while(entered.getCount()>0)assertTrue(repository.processNextPhoto("integration-revoke-io",paused,Set.of(a.file())));});
        try{assertTrue(entered.await(10,TimeUnit.SECONDS));update("UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 WHERE tenant_id::text=? AND user_id::text=?",TENANT,actor);release.countDown();old.get(20,TimeUnit.SECONDS);assertEquals(1,countJobState(a.file(),"FAILED"));}
        finally{release.countDown();threads.shutdownNow();update("UPDATE public.mop_membership SET active=true,permission_version=permission_version+1 WHERE tenant_id::text=? AND user_id::text=?",TENANT,actor);}assertEquals("AUTHORIZATION_REVOKED",operation(a).get("step"));assertEquals(422,assertThrows(DomainException.class,()->repository.contentGrant(TENANT,actor,a.file())).status);
    }
    @Test void restartReclaimsExpiredLeaseAndOldWorkerCannotOverwriteTheNewerDatabaseCompletion()throws Exception{
        Accepted a=upload(actor,UUID.randomUUID().toString());ImmutableTestStore store=new ImmutableTestStore();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService threads=Executors.newSingleThreadExecutor();Future<?> old=threads.submit(()->{PhotoObjectStore paused=pausedStore(a,store,entered,release);while(entered.getCount()>0)assertTrue(repository.processNextPhoto("integration-old",paused,Set.of(a.file())));});
        try{assertTrue(entered.await(10,TimeUnit.SECONDS));update("UPDATE public.mop_photo_job SET lease_until=clock_timestamp()-interval '1 second' WHERE file_id::text=?",a.file());PostgresPlatformRepository restarted=new PostgresPlatformRepository(pool,mapper);assertTrue(restarted.processNextPhoto("integration-new",store,Set.of(a.file())));assertEquals("SUCCEEDED",operation(a).get("status"));long version=((Number)repository.file(TENANT,actor,a.file()).orElseThrow().get("version")).longValue();release.countDown();old.get(20,TimeUnit.SECONDS);assertEquals(version,repository.file(TENANT,actor,a.file()).orElseThrow().get("version"));assertEquals("SUCCEEDED",operation(a).get("status"));assertEquals(1,store.creates.get());
            try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT fencing_token,attempts FROM public.mop_photo_job WHERE file_id::text=?")){statement.setString(1,a.file());try(ResultSet rows=statement.executeQuery()){assertTrue(rows.next());assertEquals(2,rows.getLong(1));assertEquals(2,rows.getInt(2));}}
        }finally{release.countDown();threads.shutdownNow();}
    }
}
