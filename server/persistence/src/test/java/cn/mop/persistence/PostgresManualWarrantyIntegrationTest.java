package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.assetmetadata.*;
import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.mop.persistence.PostgresManualAssetMetadataIntegrationTest.*;

/** Real V15 runtime-role business transactions. Synthetic namespace only; no external coverage claims. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresManualWarrantyIntegrationTest {
    @BeforeAll static void ready()throws SQLException{connect();}
    @AfterAll static void finish(){close();}
    static ManualWarrantyUpdate request(LocalDate start,LocalDate end,String supplier,String note){return new ManualWarrantyUpdate(new ManualWarrantyDetails(start,supplier),end,null,note);}
    static ManualWarrantyUpdate known(){return request(LocalDate.of(2026,1,1),LocalDate.of(2027,1,1),"  合成供应商😀  ","明确资料备注");}
    static Current write(Asset a,long expected,ManualWarrantyUpdate request){var access=access(engineer);return (Current)platform.idempotentAuthorized(access.actorId(),access.tenantId(),"PATCH /integration/warranty/"+a.id(),UUID.randomUUID().toString(),"a".repeat(64),access.permissionVersion(),()->metadata.authorizeCurrentAsset(access,a.id()),()->metadata.updateWarranty(access,a.id(),expected,request));}
    static long warrantyAudits(String id)throws SQLException{try(var c=pool.getConnection();var s=c.prepareStatement("SELECT count(*) FROM public.mop_audit WHERE tenant_id=?::uuid AND payload->>'action'='asset.manual_warranty.update' AND payload->>'resource_id'=?")){s.setString(1,TENANT);s.setString(2,id);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}}
    @Test void firstDeclarationKeepsIdentityAndUnknownMonitoringWhilePersistingExactFacts()throws Exception{
        Asset a=asset();Current first=write(a,1,known());assertEquals(2,first.snapshot().asset().version());assertEquals(known().details(),first.snapshot().warrantyDetails());assertEquals(known().warrantyExpiry(),first.snapshot().metadata().warrantyExpiry());assertEquals(a.name(),first.snapshot().metadata().name());assertNull(first.snapshot().metadata().brand());assertNull(first.snapshot().metadata().ownerId());assertEquals(a.managementIp(),first.snapshot().asset().managementIp());assertEquals("UNKNOWN",first.snapshot().asset().availability());assertNull(first.snapshot().asset().sampledAt());assertEquals("PENDING_ONBOARDING",first.snapshot().asset().syncState());assertEquals(1,warrantyAudits(a.id()));assertEquals(0,audits(a.id()));var history=metadata.history(access(engineer),a.id(),10,null);assertEquals(known().details(),history.get(0).warrantyDetails());assertNull(history.get(0).before().warrantyDetails());assertEquals(first,new PostgresManualAssetMetadataRepository(pool,new PostgresPlatformRepository(pool,mapper),mapper).current(access(engineer),a.id()));
    }
    @Test void warrantyUpdatePreservesExistingBaseMetadataAndExplicitlyChangesOnlyCoupledFields(){
        Asset a=asset();Current base=update(a,1,facts(a,"base note"));Current warranty=write(a,2,known());assertEquals(known().applyTo(base.snapshot().asset(),base.snapshot().metadata()),warranty.snapshot().metadata());assertEquals(base.snapshot(),metadata.history(access(engineer),a.id(),10,null).get(0).before());
    }
    @Test void oldRequestWithoutSupplementalFieldsPreservesKnownStartSupplierAndBeforeHistory(){
        Asset a=asset();Current first=write(a,1,known());var legacy=facts(first.snapshot().asset(),"legacy body");Current updated=update(a,2,legacy);assertEquals(known().details(),updated.snapshot().warrantyDetails());assertEquals(legacy,updated.snapshot().metadata());var history=metadata.history(access(engineer),a.id(),10,null);assertEquals(first.snapshot(),history.get(0).before());assertEquals(known().details(),history.get(0).warrantyDetails());assertEquals(known().details(),history.get(1).warrantyDetails());
    }
    @Test void explicitClearRemainsDeclaredAndLegacyUpdateDoesNotRevertToUndeclared(){
        Asset a=asset();write(a,1,known());Current cleared=write(a,2,request(null,null,null,"clear explicitly"));assertEquals(new ManualWarrantyDetails(null,null),cleared.snapshot().warrantyDetails());Current later=update(a,3,facts(cleared.snapshot().asset(),"legacy after clear"));assertEquals(new ManualWarrantyDetails(null,null),later.snapshot().warrantyDetails());var history=metadata.history(access(engineer),a.id(),10,null);assertEquals(known().details(),history.get(2).warrantyDetails());assertEquals(List.of(4L,3L,2L),history.stream().map(Revision::assetVersion).toList());assertEquals(List.of(3L,2L),metadata.history(access(engineer),a.id(),10,4L).stream().map(Revision::assetVersion).toList());
    }
    @Test void legacyExpiryCannotContradictPreservedStartAndFailureRollsBack()throws Exception{
        Asset a=asset();Current first=write(a,1,known());var legacy=dated(first.snapshot().asset(),LocalDate.of(2025,12,31),null,"invalid order");assertEquals(400,assertThrows(DomainException.class,()->update(a,2,legacy)).status);assertEquals(first,metadata.current(access(engineer),a.id()));assertEquals(1,metadata.history(access(engineer),a.id(),10,null).size());assertEquals(0,audits(a.id()));assertEquals(1,warrantyAudits(a.id()));
    }
    @Test void staleVersionAndSeedSourceNeverCreateRevision()throws Exception{
        Asset a=asset();Current first=write(a,1,known());assertEquals("VERSION_CONFLICT",assertThrows(DomainException.class,()->write(a,1,known())).code);assertEquals(first,metadata.current(access(engineer),a.id()));assertEquals(1,warrantyAudits(a.id()));Asset seeded=platform.list(TENANT).stream().filter(x->x.source().equals("test_seed")).findFirst().orElseThrow();assertEquals(422,assertThrows(DomainException.class,()->write(seeded,seeded.version(),known())).status);assertEquals(0,warrantyAudits(seeded.id()));
    }
    @Test void currentAuthorizationAndForeignAssetAreCheckedBeforeCachedReceipt(){
        Asset a=asset();var allowed=access(engineer);String key=UUID.randomUUID().toString(),route="PATCH /integration/warranty-auth/"+a.id();platform.idempotentAuthorized(engineer,TENANT,route,key,"a".repeat(64),allowed.permissionVersion(),()->metadata.authorizeCurrentAsset(allowed,a.id()),()->metadata.updateWarranty(allowed,a.id(),1,known()));
        String foreign=platform.list(OTHER).get(0).id();assertEquals(404,assertThrows(DomainException.class,()->platform.idempotentAuthorized(engineer,TENANT,route,key,"a".repeat(64),allowed.permissionVersion(),()->metadata.authorizeCurrentAsset(allowed,foreign),()->null)).status);
        var stale=new Access(engineer,TENANT,allowed.permissionVersion()+1,UUID.randomUUID().toString());assertEquals(401,assertThrows(DomainException.class,()->platform.idempotentAuthorized(engineer,TENANT,route,key,"a".repeat(64),allowed.permissionVersion(),()->metadata.authorizeCurrentAsset(stale,a.id()),()->null)).status);
        for(String who:List.of(viewer,tadmin)){var denied=access(who);assertEquals(403,assertThrows(DomainException.class,()->command(platform,denied,route,UUID.randomUUID().toString(),"b".repeat(64),()->metadata.updateWarranty(denied,a.id(),2,known()))).status);}
        assertThrows(IllegalStateException.class,()->metadata.updateWarranty(allowed,a.id(),2,known()));assertThrows(IllegalStateException.class,()->metadata.authorizeCurrentAsset(allowed,a.id()));
    }
    @Test void exactOriginalReceiptSurvivesNewerWarrantyAndLegacyRevisions()throws Exception{
        Asset a=asset();var auth=access(engineer);String key=UUID.randomUUID().toString(),route="PATCH /integration/warranty-replay/"+a.id();Object first=platform.idempotentAuthorized(engineer,TENANT,route,key,"a".repeat(64),auth.permissionVersion(),()->metadata.authorizeCurrentAsset(auth,a.id()),()->metadata.updateWarranty(auth,a.id(),1,known()));
        write(a,2,request(null,null,null,"clear"));Current latest=update(a,3,facts(a,"legacy"));Object replay=platform.idempotentAuthorized(engineer,TENANT,route,key,"a".repeat(64),auth.permissionVersion(),()->metadata.authorizeCurrentAsset(auth,a.id()),()->{throw new AssertionError("No replay mutation");});assertEquals(json(first),json(replay));assertEquals(latest,metadata.current(auth,a.id()));assertEquals(2,warrantyAudits(a.id()));assertEquals(1,audits(a.id()));assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(DomainException.class,()->platform.idempotentAuthorized(engineer,TENANT,route,key,"b".repeat(64),auth.permissionVersion(),()->metadata.authorizeCurrentAsset(auth,a.id()),()->null)).code);
    }
    @Test void downstreamFailureRollsBackSupplementalFactsRevisionAuditAndReceipt()throws Exception{
        Asset a=asset();var auth=access(engineer);String key=UUID.randomUUID().toString(),route="PATCH /integration/warranty-rollback/"+a.id();assertThrows(IllegalStateException.class,()->command(platform,auth,route,key,"a".repeat(64),()->{metadata.updateWarranty(auth,a.id(),1,known());throw new IllegalStateException("synthetic downstream failure");}));assertEquals(a,metadata.current(auth,a.id()).snapshot().asset());assertNull(metadata.current(auth,a.id()).snapshot().warrantyDetails());assertEquals(0,warrantyAudits(a.id()));assertTrue(metadata.history(auth,a.id(),10,null).isEmpty());Current retried=(Current)command(platform,auth,route,key,"b".repeat(64),()->metadata.updateWarranty(auth,a.id(),1,known()));assertEquals(2,retried.snapshot().asset().version());assertEquals(1,warrantyAudits(a.id()));
    }
    @Test void concurrentWarrantyAndLegacyWritersHaveOneSuccessOne409AndKeepFacts()throws Exception{
        Asset a=asset();Current first=write(a,1,known());var executor=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try{List<Future<Object>> pending=new ArrayList<>();for(boolean warranty:List.of(true,false))pending.add(executor.submit(()->{var p=new PostgresPlatformRepository(pool,mapper);var m=new PostgresManualAssetMetadataRepository(pool,p,mapper);var auth=access(engineer);ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));try{return command(p,auth,"PATCH /integration/warranty-race/"+a.id(),UUID.randomUUID().toString(),"a".repeat(64),()->warranty?m.updateWarranty(auth,a.id(),2,known()):m.update(auth,a.id(),2,facts(first.snapshot().asset(),"legacy race")));}catch(DomainException e){return e;}}));assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();List<Object> results=new ArrayList<>();for(var future:pending)results.add(future.get(15,TimeUnit.SECONDS));assertEquals(1,results.stream().filter(Current.class::isInstance).count());assertEquals(1,results.stream().filter(x->x instanceof DomainException e&&e.status==409).count());assertEquals(known().details(),metadata.current(access(engineer),a.id()).snapshot().warrantyDetails());assertEquals(2,metadata.history(access(engineer),a.id(),10,null).size());assertEquals(2,warrantyAudits(a.id())+audits(a.id()));}finally{executor.shutdownNow();}
    }
    @Test void readAndSearchReturnCurrentDetailsWithoutWritingOrUsingSupersededValues()throws Exception{
        Asset a=asset();Current first=write(a,1,known());long audits=warrantyAudits(a.id());var filter=ManualAssetFilter.fromQuery(Map.of("name",a.name()));assertEquals(List.of(first),metadata.search(access(engineer),filter,10,null,null));var dates=new AssetDateFilter(AssetDateFilter.Field.WARRANTY_EXPIRY,AssetDateFilter.Window.WITHIN_90_DAYS,LocalDate.of(2026,12,1));assertEquals(List.of(first),metadata.searchDates(access(engineer),filter,dates,10,null,null));assertEquals(audits,warrantyAudits(a.id()));Current second=write(a,2,request(null,null,"new supplier","new note"));assertEquals(second,metadata.current(access(engineer),a.id()));assertTrue(metadata.searchDates(access(engineer),filter,dates,10,null,null).isEmpty());
    }
    @Test void undeclaredBeforeSnapshotKeepsLegacyThreeFieldShapeAndOriginalLegacyReceipt()throws Exception{
        Asset a=asset();var auth=access(engineer);String key=UUID.randomUUID().toString(),route="PATCH /integration/legacy-warranty-receipt/"+a.id();
        Object first=command(platform,auth,route,key,"a".repeat(64),()->metadata.update(auth,a.id(),1,facts(a,"legacy before declaration")));
        try(var c=pool.getConnection();var s=c.prepareStatement("SELECT jsonb_exists(before_snapshot,'warranty_details') FROM public.mop_asset_manual_revision WHERE asset_id=?::uuid AND asset_version=2")){s.setString(1,a.id());try(var r=s.executeQuery()){assertTrue(r.next());assertFalse(r.getBoolean(1));}}
        write(a,2,known());Object replay=command(platform,auth,route,key,"a".repeat(64),()->{throw new AssertionError("Old receipt must not rewrite current facts");});assertEquals(json(first),json(replay));assertEquals(known().details(),metadata.current(auth,a.id()).snapshot().warrantyDetails());assertEquals(1,audits(a.id()));assertEquals(1,warrantyAudits(a.id()));
    }

}
