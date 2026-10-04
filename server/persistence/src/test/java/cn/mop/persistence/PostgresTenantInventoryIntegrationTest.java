package cn.mop.persistence;
import cn.mop.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** Existing project PG rows only. Does not create tenants/users/grants or modify their status. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresTenantInventoryIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001";
    static HikariDataSource pool;static PostgresPlatformRepository platform;static String admin;
    @BeforeAll static void connect(){String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(2);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);platform=new PostgresPlatformRepository(pool,new ObjectMapper().findAndRegisterModules());admin=actor("padmin");}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    static String actor(String role){return platform.users().stream().filter(u->platform.membership(u.id(),TENANT).filter(m->m.role().equals(role)).isPresent()).findFirst().orElseThrow().id();}
    static long version(String actor){return platform.membership(actor,TENANT).orElseThrow().permissionVersion();}
    static List<TenantInventory> read(String actor,String after,int limit){return platform.authorizedTenantInventory(TENANT,actor,version(actor),new TenantInventory.Query(after,limit));}
    static long scalar(String sql,String tenant)throws SQLException{try(var c=pool.getConnection();var s=c.prepareStatement(sql)){if(tenant!=null)s.setString(1,tenant);try(var r=s.executeQuery()){assertTrue(r.next());return r.getLong(1);}}}
    @Test void onlyExistingActivePerTargetPlatformAdminMembershipsAreReturned(){var values=read(admin,null,101);assertFalse(values.isEmpty());var expected=platform.allowedTenants(admin).stream().filter(t->platform.membership(admin,t.id()).orElseThrow().role().equals("padmin")).map(Tenant::id).sorted().toList();assertEquals(expected,values.stream().map(TenantInventory::id).toList());for(var value:values){assertEquals(platform.tenant(value.id()).name(),value.name());assertNotNull(value.createdAt());}}
    @Test void currentNonAdminRolesCannotEnumerateOtherTenantInventory(){for(String role:List.of("msp","tadmin","viewer")){String actor=actor(role);assertEquals(403,assertThrows(DomainException.class,()->read(actor,null,15)).status);}}
    @Test void staleMissingAndMalformedAuthorizationRejectBeforeProjection(){for(String who:new String[]{UUID.randomUUID().toString(),"bad",null})assertEquals(401,assertThrows(DomainException.class,()->platform.authorizedTenantInventory(TENANT,who,1,new TenantInventory.Query(null,15))).status);assertEquals(401,assertThrows(DomainException.class,()->platform.authorizedTenantInventory(TENANT,admin,version(admin)+1,new TenantInventory.Query(null,15))).status);assertEquals(401,assertThrows(DomainException.class,()->platform.authorizedTenantInventory(UUID.randomUUID().toString(),admin,version(admin),new TenantInventory.Query(null,15))).status);}
    @Test void orderedKeysetDoesNotDuplicateOrSkipExistingAuthorizedTenants(){var all=read(admin,null,101);List<TenantInventory> paged=new ArrayList<>();String after=null;for(int i=0;i<=all.size();i++){var page=read(admin,after,1);assertTrue(page.size()<=1);if(page.isEmpty())break;paged.addAll(page);after=page.get(0).id();}assertEquals(all,paged);assertTrue(read(admin,"ffffffff-ffff-ffff-ffff-ffffffffffff",15).isEmpty());}
    @Test void countsAreRecordedRowsAndActiveUsersNotMonitoringOrLicenses()throws SQLException{for(var value:read(admin,null,101)){assertEquals(scalar("SELECT count(*) FROM public.mop_asset WHERE tenant_id=?::uuid",value.id()),value.registeredAssetCount());assertEquals(scalar("SELECT count(*) FROM public.mop_site WHERE tenant_id=?::uuid",value.id()),value.registeredSiteCount());assertEquals(scalar("SELECT count(*) FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.active AND u.active",value.id()),value.activeMemberCount());}}
    @Test void readsDoNotWriteAuditOrIdentityAndOutputListIsImmutable()throws SQLException{long audit=scalar("SELECT count(*) FROM public.mop_audit",null),users=scalar("SELECT count(*) FROM public.mop_user",null),members=scalar("SELECT count(*) FROM public.mop_membership",null);var rows=read(admin,null,15);assertThrows(UnsupportedOperationException.class,rows::clear);assertEquals(audit,scalar("SELECT count(*) FROM public.mop_audit",null));assertEquals(users,scalar("SELECT count(*) FROM public.mop_user",null));assertEquals(members,scalar("SELECT count(*) FROM public.mop_membership",null));}
    @Test void queryMissingCannotBecomeUnboundedEnumeration(){assertEquals(400,assertThrows(DomainException.class,()->platform.authorizedTenantInventory(TENANT,admin,version(admin),null)).status);}
}
