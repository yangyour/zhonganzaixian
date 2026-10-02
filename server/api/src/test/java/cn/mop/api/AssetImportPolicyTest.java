package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetimport.source.ImportFormat;
import cn.mop.domain.assetimport.source.ImportLimits;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssetImportPolicyTest {
    private final AssetImportPolicy policy=new AssetImportPolicy();
    @Test void exactTechnicalBoundaryAndBothContractFormatsAreAllowedWithoutClaimingActualParsing(){
        assertDoesNotThrow(()->policy.validate("asset_import_source","MOP-FAKE.csv",ImportFormat.CSV,"text/csv; charset=UTF-8",ImportLimits.MAX_SOURCE_BYTES));
        assertDoesNotThrow(()->policy.validate("asset_import_source","MOP-FAKE.XLSX",ImportFormat.XLSX,"application/octet-stream",1));
        assertEquals(false,policy.dto().get("can_import"));assertEquals("CONFIG_PENDING",policy.dto().get("business_status"));
        assertEquals("UNSCANNED",policy.dto().get("scan_status"));assertNull(policy.dto().get("retention_until"));
    }
    @Test void mismatchedDeclarationsPhotoPurposeAndUnsafeNamesAreRejected(){
        for(String name:new String[]{"../MOP-FAKE.csv","a\\MOP-FAKE.csv","MOP-FAKE.csv\n"," ","x".repeat(201)+".csv"})
            assertThrows(DomainException.class,()->policy.validate("asset_import_source",name,ImportFormat.CSV,"text/csv",1));
        assertThrows(DomainException.class,()->policy.validate("customer_report_photo","MOP-FAKE.csv",ImportFormat.CSV,"text/csv",1));
        assertThrows(DomainException.class,()->policy.validate("asset_import_source","MOP-FAKE.xlsx",ImportFormat.CSV,"text/csv",1));
        assertThrows(DomainException.class,()->policy.validate("asset_import_source","MOP-FAKE.csv",ImportFormat.CSV,"image/png",1));
        assertThrows(DomainException.class,()->policy.validate("asset_import_source","MOP-FAKE.csv",ImportFormat.CSV,"text/csv",ImportLimits.MAX_SOURCE_BYTES+1));
        assertThrows(DomainException.class,()->policy.validate("asset_import_source","MOP-FAKE.csv",ImportFormat.CSV,"text/csv",0));
    }
}
