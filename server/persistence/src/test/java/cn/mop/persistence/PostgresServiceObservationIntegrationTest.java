package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.dashboard.ServiceObservationReader.*;
import cn.mop.domain.dashboard.ServiceObservationIntervals.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static cn.mop.persistence.PostgresReportCaptureIsolatedIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Designated PostgreSQL only. Existing explicitly isolated identities/assets, no fixture writes or DDL. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_SERVICE_OBSERVATION_PG_TESTS",matches="true")
class PostgresServiceObservationIntegrationTest {
    static PostgresServiceObservationReader reader;
    static Access access;
    static Definition definition(String assetId){return new Definition(UUID.fromString(assetId),"SYNTHETIC service observation",List.of(UUID.fromString(assetId)),FailureMode.ANY_ASSET_UNAVAILABLE,MaintenanceMode.ANY_ASSET_APPROVED,null);}
    @BeforeAll static void setup() throws Exception {
        PostgresReportCaptureIsolatedIntegrationTest.connect();
        var member=platform.membership(actor,tenant).orElseThrow();
        assertTrue(Set.of("msp","padmin").contains(member.role()));
        access=new Access(UUID.fromString(tenant),UUID.fromString(actor),member.permissionVersion());
        reader=new PostgresServiceObservationReader(pool,platform,true,t->List.of(definition(asset)));
    }
    @AfterAll static void finish(){PostgresReportCaptureIsolatedIntegrationTest.close();}
    @Test void realSqlReturnsExactClockAndExplicitUnknownCoverage(){var result=reader.read(access);assertEquals(access.tenantId(),result.tenantId());assertEquals(access.actorId(),result.actorId());assertEquals(0,result.observedAt().getNano()%1000000);assertEquals(result.observedAt().minusSeconds(86400),result.timelineStart());assertEquals(1,result.services().size());assertEquals(UUID.fromString(asset),result.services().get(0).assets().get(0).id());assertNull(result.services().get(0).targetPct());assertEquals("UNKNOWN",result.services().get(0).healthState());}
    @Test void foreignAssetMappingIsNotReadable(){var foreign=new PostgresServiceObservationReader(pool,platform,true,t->List.of(definition(foreignAsset)));assertEquals(404,assertThrows(DomainException.class,()->foreign.read(access)).status);}
    @Test void staleMembershipVersionFailsClosed(){assertEquals(401,assertThrows(DomainException.class,()->reader.read(new Access(access.tenantId(),access.actorId(),access.permissionVersion()+1))).status);}
    @Test void disabledOrUnconfiguredIsNotAnEmptySuccess(){assertEquals(503,assertThrows(DomainException.class,()->new PostgresServiceObservationReader(pool,platform,false,t->List.of(definition(asset))).read(access)).status);assertEquals(503,assertThrows(DomainException.class,()->new PostgresServiceObservationReader(pool,platform,true,t->List.of()).read(access)).status);}
    @Test void repeatedReadsDoNotAppendBusinessRows() throws Exception {var before=counts();reader.read(access);reader.read(access);assertEquals(before,counts());}
    static Map<String,Long> counts() throws Exception {var result=new TreeMap<String,Long>();try(var c=pool.getConnection()){for(var table:List.of("mop_asset","mop_monitoring_event","mop_maintenance_window","mop_audit","mop_idempotency")){try(var s=c.prepareStatement("SELECT count(*) FROM public."+table+" WHERE tenant_id=?::uuid")){s.setString(1,tenant);try(var rows=s.executeQuery()){assertTrue(rows.next());result.put(table,rows.getLong(1));}}}}return result;}
}
