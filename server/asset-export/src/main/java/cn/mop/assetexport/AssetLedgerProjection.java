package cn.mop.assetexport;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository.Current;
import cn.mop.domain.assetimport.AssetImportPrechecker.Column;
import java.util.*;

/** Maps a caller-supplied current authorized snapshot, never independently fetches or grants access. */
public final class AssetLedgerProjection {
    private AssetLedgerProjection() {}
    public static AssetLedgerWorkbook.Row fromCurrent(UUID tenantId,Current current){
        if(tenantId==null||current==null||current.snapshot()==null)throw invalid();
        var snapshot=current.snapshot();var asset=snapshot.asset();
        UUID ownerTenant=uuid(asset.tenantId()),assetId=uuid(asset.id());
        if(!tenantId.equals(ownerTenant))throw new DomainException(404,"OBJECT_NOT_FOUND","Object not found in export scope");
        var metadata=snapshot.metadata();
        if(current.editBlockers().contains("MANUAL_METADATA_CORE_DRIFT")||metadata!=null&&(!Objects.equals(metadata.name(),asset.name())||!Objects.equals(metadata.type(),asset.type())||!Objects.equals(metadata.siteId(),asset.siteId())))throw new DomainException(503,"UPSTREAM_UNAVAILABLE","Recorded metadata and asset identity require reconciliation");
        var cells=new EnumMap<Column,String>(Column.class);for(Column column:Column.values())cells.put(column,null);
        cells.put(Column.NAME,asset.name());cells.put(Column.TYPE,asset.type());cells.put(Column.ADDRESS,asset.managementIp());cells.put(Column.SITE,asset.siteId());
        if(metadata!=null){
            cells.put(Column.BRAND,metadata.brand());cells.put(Column.MODEL,metadata.model());cells.put(Column.SERIAL_NUMBER,metadata.serialNumber());cells.put(Column.LOCATION,metadata.location());cells.put(Column.OWNER,metadata.ownerId());cells.put(Column.BUSINESS,metadata.businessDescription());cells.put(Column.PURCHASE_DATE,date(metadata.purchaseDate()));cells.put(Column.WARRANTY_EXPIRY,date(metadata.warrantyExpiry()));cells.put(Column.OVERDUE_SERVICE_DATE,date(metadata.overdueServiceDate()));cells.put(Column.COLLECTION_METHOD,metadata.collectionMethodNote());cells.put(Column.NOTE,metadata.note());
        }
        // Current manual declarations do not prove a service provider's coverage decision.
        return new AssetLedgerWorkbook.Row(tenantId,assetId,cells,null);
    }
    private static String date(java.time.LocalDate value){return value==null?null:value.toString();}
    private static UUID uuid(String value){if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid();return UUID.fromString(value);}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","Explicit canonical asset snapshot required");}
}
