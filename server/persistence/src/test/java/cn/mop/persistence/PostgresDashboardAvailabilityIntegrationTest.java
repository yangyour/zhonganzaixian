package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.dashboard.*;
import cn.mop.domain.dashboard.DashboardAvailabilityReader.Access;
import cn.mop.domain.dashboard.DashboardAvailabilitySnapshot.TrendWindow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** Existing PG authorization and observed clock only. This is not real monitoring or dashboard metric integration. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresDashboardAvailabilityIntegrationTest {
    static final String A="55000000-0000-4000-8000-000000000001",B="55000000-0000-4000-8000-000000000002";
    static HikariDataSource pool;static PostgresPlatformRepository platform;static PostgresDashboardAvailabilityReader reader;
    @BeforeAll static void setup(){String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(3);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);platform=new PostgresPlatformRepository(pool,new ObjectMapper());reader=new PostgresDashboardAvailabilityReader(pool,platform);}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    static Access actor(String role,String tenant){var u=platform.users().stream().filter(x->platform.membership(x.id(),tenant).filter(m->m.active()&&m.role().equals(role)).isPresent()).findFirst().orElseThrow();return new Access(u.id(),tenant,platform.membership(u.id(),tenant).orElseThrow().permissionVersion());}
    static String count(String table)throws Exception{try(var c=pool.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*)::text FROM public."+table)){assertTrue(r.next());return r.getString(1);}}
    static List<String> counts()throws Exception{List<String> out=new ArrayList<>();for(String t:List.of("mop_asset","mop_ticket","mop_audit","mop_idempotency","mop_membership","mop_notification_preference_revision"))out.add(count(t));return out;}
    @Test void allFourExistingRolesReadSameFixedLayoutUnderActualMembership(){for(String role:List.of("padmin","msp","tadmin","viewer")){var a=actor(role,A);var s=reader.read(a,TrendWindow.HOURS_24);assertEquals(UUID.fromString(a.tenantId()),s.tenantId());assertEquals(UUID.fromString(a.actorId()),s.actorId());assertEquals(6,s.kpis().size());assertEquals(6,s.panels().size());assertFalse(s.metricSamplesLoaded());assertTrue(s.kpis().stream().allMatch(k->k.value()==null&&k.sampleCount()==null));}}
    static Instant databaseClock()throws Exception{try(var c=pool.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT clock_timestamp()")){assertTrue(r.next());return r.getTimestamp(1).toInstant();}}
    @Test void databaseObservedClockIsMillisecondAndWithinActualCallWindow()throws Exception{var before=databaseClock().minusMillis(1);var s=reader.read(actor("viewer",A),TrendWindow.DAYS_7);var after=databaseClock().plusMillis(1);assertFalse(s.generatedAt().isBefore(before));assertFalse(s.generatedAt().isAfter(after));assertEquals(0,s.generatedAt().getNano()%1000000);assertFalse(s.clockAccuracyVerified());}
    @Test void stalePermissionVersionIsRejected(){var a=actor("viewer",A);assertEquals(401,assertThrows(DomainException.class,()->reader.read(new Access(a.actorId(),A,a.permissionVersion()+1),TrendWindow.HOURS_24)).status);}
    @Test void anotherTenantWithoutMembershipAndMissingActorAreNotAuthorized(){var a=actor("viewer",A);assertEquals(401,assertThrows(DomainException.class,()->reader.read(new Access(a.actorId(),B,a.permissionVersion()),TrendWindow.HOURS_24)).status);assertEquals(401,assertThrows(DomainException.class,()->reader.read(new Access(UUID.randomUUID().toString(),A,1),TrendWindow.HOURS_24)).status);}
    @Test void internalActorMustAuthorizeTheSpecificTargetTenant(){var a=actor("padmin",A);var b=actor("padmin",B);var first=reader.read(a,TrendWindow.HOURS_24);var second=reader.read(b,TrendWindow.DAYS_30);assertNotEquals(first.tenantId(),second.tenantId());assertEquals(first.actorId(),second.actorId());assertEquals(TrendWindow.DAYS_30,second.requestedWindow());}
    @Test void repeatedQueriesNeverAppendAuditReceiptOrBusinessRecords()throws Exception{var before=counts();for(var w:TrendWindow.values())reader.read(actor("msp",A),w);assertEquals(before,counts());}
    @Test void invalidRequestAndNestedWriteTransactionFailWithoutChanges()throws Exception{var a=actor("msp",A);var before=counts();assertEquals(400,assertThrows(DomainException.class,()->reader.read(a,null)).status);assertEquals(400,assertThrows(DomainException.class,()->reader.read(new Access("bad",A,1),TrendWindow.HOURS_24)).status);assertThrows(IllegalStateException.class,()->platform.idempotent(a.actorId(),A,"GET /integration/dashboard-nested",UUID.randomUUID().toString(),"a".repeat(64),a.permissionVersion(),()->reader.read(a,TrendWindow.HOURS_24)));assertEquals(before,counts());}
    @Test void actualSqlFailureReturns503InsteadOfAnUnknownSuccess()throws Exception{
        DataSource altered=(DataSource)java.lang.reflect.Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class[]{DataSource.class},(p,m,args)->{if(m.getName().equals("getConnection")){Connection real=pool.getConnection();return java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(cp,cm,ca)->{if(cm.getName().equals("prepareStatement")){try(var s=real.createStatement()){s.execute("SELECT 1/0");}}return invoke(cm,real,ca);});}return invoke(m,pool,args);});
        var broken=new PostgresDashboardAvailabilityReader(altered,platform);var before=counts();assertEquals(503,assertThrows(DomainException.class,()->broken.read(actor("viewer",A),TrendWindow.HOURS_24)).status);assertEquals(before,counts());
    }
    static Object invoke(java.lang.reflect.Method method,Object value,Object[] args)throws Throwable{try{return method.invoke(value,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}}
}
