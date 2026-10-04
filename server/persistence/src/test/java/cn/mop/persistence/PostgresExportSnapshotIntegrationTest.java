package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.assetexport.*;
import cn.mop.domain.assetexport.AssetExportJobState.*;
import com.fasterxml.jackson.databind.*;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** Existing real PG rows only; no new assets, memberships, fixtures, schema or storage. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresExportSnapshotIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001";
    static HikariDataSource pool;static PostgresPlatformRepository platform;static PostgresManualAssetMetadataRepository repository;static ObjectMapper mapper;static String admin;static Asset sample;
    @BeforeAll static void connect(){String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(3);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);mapper=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);platform=new PostgresPlatformRepository(pool,mapper);repository=new PostgresManualAssetMetadataRepository(pool,platform,mapper);admin=actor("padmin");sample=platform.list(TENANT).stream().filter(a->a.name().length()<=100).findFirst().orElseThrow();}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    static String actor(String role){return platform.users().stream().filter(u->platform.membership(u.id(),TENANT).filter(m->m.role().equals(role)).isPresent()).findFirst().orElseThrow().id();}
    static Command command(String actor,String role,Filters filters){return new Command(new Access(UUID.fromString(TENANT),UUID.fromString(actor),platform.membership(actor,TENANT).orElseThrow().permissionVersion(),role,true),filters);}
    static Filters exact(){return new Filters(sample.type(),sample.status(),sample.siteId(),sample.name());}
    static List<String> expected(Filters f){var ids=new ArrayList<String>();java.time.Instant after=null;String id=null;while(true){var page=platform.authorizedAssets(TENANT,admin,platform.membership(admin,TENANT).orElseThrow().permissionVersion(),new AssetReadQuery(f.type(),f.status(),f.siteId(),f.query(),101,after,id));if(page.isEmpty())return ids;ids.addAll(page.stream().map(Asset::id).toList());var tail=page.get(page.size()-1);after=tail.createdAt();id=tail.id();}}
    @Test void exactPredicatesMatchExistingMainLedgerAndAllReturnedRowsRemainTenantScoped(){var f=exact();var selection=repository.select(command(admin,"padmin",f));assertFalse(selection.rows().isEmpty());assertEquals(expected(f),selection.rows().stream().map(r->r.snapshot().asset().id()).toList());assertTrue(selection.rows().stream().allMatch(r->r.snapshot().asset().tenantId().equals(TENANT)));assertNotNull(selection.transactionStartedAt());}
    @Test void wholeSelectionIsCompleteOrExplicitlyRejectedNeverTruncated(){var f=new Filters(null,null,null,null);var ids=expected(f);if(ids.size()>1000){assertEquals(413,assertThrows(DomainException.class,()->repository.select(command(admin,"padmin",f))).status);}else{var result=repository.select(command(admin,"padmin",f));assertEquals(ids,result.rows().stream().map(r->r.snapshot().asset().id()).toList());assertThrows(UnsupportedOperationException.class,()->result.rows().clear());}}
    @Test void literalEmptyUnicodeAndAddressPredicatesMatchMainLedger(){for(var f:List.of(new Filters(null,null,"",null),new Filters(null,null,null,"%_"),new Filters(null,null,null,"' OR '1'='1"),new Filters(null,null,null,sample.managementIp()),new Filters(null,null,null,"ä"))){var ids=expected(f);assertTrue(ids.size()<=1000);assertEquals(ids,repository.select(command(admin,"padmin",f)).rows().stream().map(r->r.snapshot().asset().id()).toList());}}
    @Test void currentInternalEngineerCanSelectButForgedCustomerRoleCannot(){assertFalse(repository.select(command(actor("msp"),"msp",exact())).rows().isEmpty());for(String role:List.of("viewer","tadmin"))assertEquals(403,assertThrows(DomainException.class,()->repository.select(command(actor(role),"msp",exact()))).status);}
    @Test void changedRoleAndVersionOrMissingMembershipFailBeforeRows(){var c=command(admin,"padmin",exact());var a=c.acceptedAccess();for(var changed:List.of(new Access(a.tenantId(),a.actorId(),a.permissionVersion()+1,a.role(),true),new Access(a.tenantId(),a.actorId(),a.permissionVersion(),"msp",true),new Access(a.tenantId(),UUID.randomUUID(),a.permissionVersion(),a.role(),true))){assertEquals(401,assertThrows(DomainException.class,()->repository.select(new Command(changed,c.filters()))).status);}}
    @Test void foreignSiteHasNoRowsAndUnknownTenantHasNoMembership(){assertTrue(repository.select(command(admin,"padmin",new Filters(null,null,"site-b-main",null))).rows().isEmpty());var c=command(admin,"padmin",exact());var a=c.acceptedAccess();assertEquals(401,assertThrows(DomainException.class,()->repository.select(new Command(new Access(UUID.randomUUID(),a.actorId(),a.permissionVersion(),a.role(),true),c.filters()))).status);}
    @Test void invalidNullCommandFailsBeforeOpeningQuery(){assertEquals(400,assertThrows(DomainException.class,()->repository.select(null)).status);}
    static List<Long> counts()throws SQLException{var result=new ArrayList<Long>();try(var c=pool.getConnection();var s=c.createStatement()){for(String table:List.of("mop_asset","mop_asset_manual_revision","mop_audit","mop_membership")){try(var r=s.executeQuery("SELECT count(*) FROM public."+table)){r.next();result.add(r.getLong(1));}}}return result;}
    @Test void successfulAndDeniedReadsDoNotChangeAssetsAuditOrMembership()throws Exception{var before=counts();repository.select(command(admin,"padmin",exact()));assertThrows(DomainException.class,()->repository.select(command(admin,"msp",exact())));assertEquals(before,counts());}
    @Test void metadataMatchesExistingPerAssetReaderWithinSelectedRows()throws Exception{String name;try(var c=pool.getConnection();var s=c.prepareStatement("SELECT a.name FROM public.mop_asset a JOIN public.mop_asset_manual_details d ON d.tenant_id=a.tenant_id AND d.asset_id=a.id WHERE a.tenant_id=?::uuid ORDER BY a.id LIMIT 1")){s.setString(1,TENANT);try(var r=s.executeQuery()){assertTrue(r.next(),"Existing real metadata required");name=r.getString(1);}}var selected=repository.select(command(admin,"padmin",new Filters(null,null,null,name)));assertFalse(selected.rows().isEmpty());assertTrue(selected.rows().stream().anyMatch(r->r.snapshot().metadata()!=null));for(var row:selected.rows()){var asset=row.snapshot().asset();var a=new cn.mop.domain.assetmetadata.ManualAssetMetadataRepository.Access(admin,TENANT,platform.membership(admin,TENANT).orElseThrow().permissionVersion(),"export-readonly-test");assertEquals(repository.current(a,asset.id()),row);}}
    @Test void oneRealConnectionRetainsReadonlyRepeatableReadAcrossEveryStatement()throws Exception{
        var connections=new java.util.concurrent.atomic.AtomicInteger();var checks=new java.util.concurrent.atomic.AtomicInteger();var commits=new java.util.concurrent.atomic.AtomicInteger();
        javax.sql.DataSource observed=(javax.sql.DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{javax.sql.DataSource.class},(proxy,method,args)->{
            if(method.getName().equals("getConnection")){
                connections.incrementAndGet();Connection real=pool.getConnection();
                return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(cp,cm,ca)->{
                    if(cm.getName().equals("prepareStatement")){assertFalse(real.getAutoCommit());assertTrue(real.isReadOnly());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,real.getTransactionIsolation());try(var st=real.createStatement();var rs=st.executeQuery("SELECT current_setting('transaction_isolation'),current_setting('transaction_read_only')")){rs.next();assertEquals("repeatable read",rs.getString(1));assertEquals("on",rs.getString(2));}checks.incrementAndGet();}
                    if(cm.getName().equals("commit"))commits.incrementAndGet();
                    try{return cm.invoke(real,ca);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                });
            }
            try{return method.invoke(pool,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
        });
        var inspected=new PostgresManualAssetMetadataRepository(observed,platform,mapper);assertFalse(inspected.select(command(admin,"padmin",exact())).rows().isEmpty());assertEquals(1,connections.get());assertEquals(1,commits.get());assertTrue(checks.get()>=7);
    }
    @Test void callerWriteTransactionCannotBeSilentlyJoinedOrSplit(){var before=assertDoesNotThrow(()->counts());assertThrows(IllegalStateException.class,()->platform.idempotent(admin,TENANT,"POST /integration/export-reject-nesting",UUID.randomUUID().toString(),"a".repeat(64),()->repository.select(command(admin,"padmin",exact()))));assertEquals(before,assertDoesNotThrow(()->counts()));}

}
