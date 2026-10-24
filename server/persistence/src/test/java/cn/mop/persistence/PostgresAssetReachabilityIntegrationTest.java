package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.dashboard.AssetReachability.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static cn.mop.persistence.PostgresReportCaptureIsolatedIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;
/** Existing designated isolated identity and assets only; zero source/provider IO or fixture writes. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_ASSET_REACHABILITY_PG_TESTS",matches="true")
class PostgresAssetReachabilityIntegrationTest {
 static Reader reader;static Access access;
 @BeforeAll static void setup()throws Exception{PostgresReportCaptureIsolatedIntegrationTest.connect();access=new Access(UUID.fromString(tenant),UUID.fromString(actor),platform.membership(actor,tenant).orElseThrow().permissionVersion());reader=new PostgresAssetReachabilityReader(pool,platform);}
 @AfterAll static void finish(){PostgresReportCaptureIsolatedIntegrationTest.close();}
 @Test void readsEntireAuthorizedLedgerWithDatabaseClock()throws Exception{var result=reader.read(access);assertEquals(access,result.access());assertEquals(0,result.observedAt().getNano()%1000000);try(var c=pool.getConnection();var s=c.prepareStatement("SELECT count(*) FROM public.mop_asset WHERE tenant_id=?::uuid")){s.setString(1,tenant);try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(r.getLong(1),result.assets().size());}}assertTrue(result.assets().stream().anyMatch(a->a.id().toString().equals(asset)));assertFalse(result.assets().stream().anyMatch(a->a.id().toString().equals(foreignAsset)));}
 @Test void staleVersionFailsClosed(){assertEquals(401,assertThrows(DomainException.class,()->reader.read(new Access(access.tenantId(),access.actorId(),access.permissionVersion()+1))).status);}
 @Test void repeatedReadsAreStableWithoutProviderSampling(){assertEquals(reader.read(access).assets(),reader.read(access).assets());}
}
