package cn.mop.persistence;
import cn.mop.domain.DomainException;import cn.mop.domain.dashboard.NotificationAcceptance.*;
import java.util.*;import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static cn.mop.persistence.PostgresReportCaptureIsolatedIntegrationTest.*;import static org.junit.jupiter.api.Assertions.*;
/** Existing designated isolated namespace; zero sends, DDL or fixture writes. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_NOTIFICATION_ACCEPTANCE_PG_TESTS",matches="true")
class PostgresNotificationAcceptanceIntegrationTest {
 static Reader reader;static Access access;
 @BeforeAll static void setup()throws Exception{PostgresReportCaptureIsolatedIntegrationTest.connect();access=new Access(UUID.fromString(tenant),UUID.fromString(actor),platform.membership(actor,tenant).orElseThrow().permissionVersion());reader=new PostgresNotificationAcceptanceReader(pool,platform);}
 @AfterAll static void finish(){PostgresReportCaptureIsolatedIntegrationTest.close();}
 @Test void sameSnapshotCountsAllBusinessSubmissionsWithoutDeliveryClaims(){var value=reader.read(access);assertEquals(access.tenantId(),value.tenantId());assertEquals(value.observedAt().minusSeconds(86400),value.windowStart());assertEquals(value.items().size(),value.counts().total());assertEquals(value.counts().total(),value.byChannel().get("EMAIL").total()+value.byChannel().get("WECHAT_WORK").total());assertTrue(value.items().stream().noneMatch(e->e.status().equals("DELIVERED")));}
 @Test void stalePermissionFailsClosed(){assertEquals(401,assertThrows(DomainException.class,()->reader.read(new Access(access.tenantId(),access.actorId(),access.permissionVersion()+1))).status);}
 @Test void missingActorIsNotAnEmptySuccess(){assertThrows(DomainException.class,()->reader.read(new Access(access.tenantId(),UUID.randomUUID(),1)));}
}
