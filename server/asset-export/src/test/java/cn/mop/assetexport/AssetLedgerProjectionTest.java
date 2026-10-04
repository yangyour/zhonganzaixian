package cn.mop.assetexport;
import cn.mop.domain.*;
import cn.mop.domain.assetmetadata.*;
import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository.*;
import cn.mop.domain.assetimport.AssetImportPrechecker.Column;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AssetLedgerProjectionTest {
    final UUID tenant=UUID.randomUUID(),assetId=UUID.randomUUID();final Instant t=Instant.EPOCH;
    Asset asset(String source){return new Asset(assetId.toString(),tenant.toString(),"原设备","router","192.0.2.1","site-a","unknown","DRAFT",source,null,"NOT_ONBOARDED",2,t,t,"UNKNOWN","PENDING_ONBOARDING");}
    ManualAssetMetadata metadata(){return new ManualAssetMetadata("原设备","router","site-a","原品牌","原型号","0000123","原位置",UUID.randomUUID().toString(),"原业务",LocalDate.of(2020,1,1),LocalDate.of(2021,1,1),null,"原采集说明","=原备注\n保留");}
    Current current(ManualAssetMetadata m){return new Current(new Snapshot(asset("manual"),m,m==null?null:2L),null,m==null?null:t,List.of());}
    @Test void exactFieldsAndDatesAreProjectedWithoutCleaningOrFloatingPoint(){var m=metadata();var row=AssetLedgerProjection.fromCurrent(tenant,current(m));assertEquals("原设备",row.cells().get(Column.NAME));assertEquals("router",row.cells().get(Column.TYPE));assertEquals("192.0.2.1",row.cells().get(Column.ADDRESS));assertEquals("site-a",row.cells().get(Column.SITE));assertEquals(m.ownerId(),row.cells().get(Column.OWNER));assertEquals("0000123",row.cells().get(Column.SERIAL_NUMBER));assertEquals("2021-01-01",row.cells().get(Column.WARRANTY_EXPIRY));assertEquals(m.note(),row.cells().get(Column.NOTE));}
    @Test void unknownMetadataStaysBlankRatherThanInventingDefaults(){var row=AssetLedgerProjection.fromCurrent(tenant,current(null));assertEquals(15,row.cells().size());for(Column column:Column.values())if(!Set.of(Column.NAME,Column.TYPE,Column.ADDRESS,Column.SITE).contains(column))assertNull(row.cells().get(column));}
    @Test void pastWarrantyDateDoesNotBecomeAnAssertedCoverageStatus(){assertNull(AssetLedgerProjection.fromCurrent(tenant,current(metadata())).warrantyStatus());}
    @Test void foreignTenantDeclarationRejectsBeforeProjection(){assertEquals(404,assertThrows(DomainException.class,()->AssetLedgerProjection.fromCurrent(UUID.randomUUID(),current(null))).status);}
    @Test void mismatchedCoreFactsDoNotProduceACombinedRow(){var m=metadata();var mismatch=new ManualAssetMetadata("不同设备",m.type(),m.siteId(),m.brand(),m.model(),m.serialNumber(),m.location(),m.ownerId(),m.businessDescription(),m.purchaseDate(),m.warrantyExpiry(),m.overdueServiceDate(),m.collectionMethodNote(),m.note());assertEquals(503,assertThrows(DomainException.class,()->AssetLedgerProjection.fromCurrent(tenant,current(mismatch))).status);}
    @Test void explicitCoreDriftBlockerRejectsEvenWhenDisplayedFieldsAppearEqual(){var current=current(metadata());var drift=new Current(current.snapshot(),current.recordedBy(),current.recordedAt(),List.of("MANUAL_METADATA_CORE_DRIFT"));assertEquals(503,assertThrows(DomainException.class,()->AssetLedgerProjection.fromCurrent(tenant,drift)).status);}
    @Test void readOnlyProjectionDoesNotTurnEditBlockersIntoMissingData(){var input=new Current(new Snapshot(asset("test_seed"),null,null),null,null,List.of("MANUAL_DRAFT_REQUIRED"));assertEquals("原设备",AssetLedgerProjection.fromCurrent(tenant,input).cells().get(Column.NAME));}
    @Test void returnedColumnMapIsImmutableAndContainsNoExtraMetadataColumns(){var row=AssetLedgerProjection.fromCurrent(tenant,current(metadata()));assertEquals(EnumSet.allOf(Column.class),row.cells().keySet());assertThrows(UnsupportedOperationException.class,()->row.cells().put(Column.NOTE,"changed"));}
    @Test void nullScopeOrMissingSnapshotIsInvalid(){assertThrows(DomainException.class,()->AssetLedgerProjection.fromCurrent(null,current(null)));assertThrows(DomainException.class,()->AssetLedgerProjection.fromCurrent(tenant,null));}
}
