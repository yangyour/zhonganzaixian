package cn.mop.persistence;

import cn.mop.domain.DomainException;

import cn.mop.domain.assetmetadata.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManualAssetFilterTest {
    @Test void preservesLiteralTextAndAllConjunctiveFields(){var filter=ManualAssetFilter.fromQuery(Map.of("brand"," %_品牌 ","type","router","note","明确\n备注","purchase_date","2024-02-29"));assertEquals(" %_品牌 ",filter.values().get(ManualAssetFilter.Field.BRAND));assertEquals(4,filter.values().size());assertTrue(filter.unknownFields().isEmpty());}
    @Test void explicitUnknownDoesNotBecomeBlankOrExpired(){var filter=ManualAssetFilter.fromQuery(Map.of("unknown_fields","owner_id,warranty_expiry,note"));assertEquals(3,filter.unknownFields().size());assertTrue(filter.values().isEmpty());assertThrows(DomainException.class,()->ManualAssetFilter.fromQuery(Map.of("unknown_fields","owner_id","owner_id","55000000-0000-4000-8000-000000000001")));}
    @Test void rejectsUnknownKeysDuplicatesAndRequiredUnknowns(){for(var query:List.of(Map.of("tenant_id","x"),Map.of("warranty_status","expired"),Map.of("unknown_fields","brand,brand"),Map.of("unknown_fields","name"),Map.of("unknown_fields","brand,")))assertThrows(DomainException.class,()->ManualAssetFilter.fromQuery(query));}
    @Test void validatesDatesAndReferenceSyntaxWithoutInventingDefaults(){for(String date:List.of("0000-01-01","2026-02-30","2026-10-02T00:00:00Z","123"))assertThrows(DomainException.class,()->ManualAssetFilter.fromQuery(Map.of("warranty_expiry",date)));assertEquals("55000000-0000-4000-8000-0000000000ab",ManualAssetFilter.fromQuery(Map.of("owner_id","55000000-0000-4000-8000-0000000000AB")).values().get(ManualAssetFilter.Field.OWNER_ID));assertThrows(DomainException.class,()->ManualAssetFilter.fromQuery(Map.of("type","Router")));}
    @Test void usesUnicodeBoundsAndRejectsBlankControlsAndSurrogates(){assertDoesNotThrow(()->ManualAssetFilter.fromQuery(Map.of("brand","😀".repeat(100))));for(String value:List.of("😀".repeat(101),"\u00a0","\ud800","x\n"))assertThrows(DomainException.class,()->ManualAssetFilter.fromQuery(Map.of("brand",value)));}
    @Test void freezesInputAndExposesAnEmptyFilterWithoutDefaults(){var values=new EnumMap<ManualAssetFilter.Field,String>(ManualAssetFilter.Field.class);values.put(ManualAssetFilter.Field.BRAND,"original");var filter=new ManualAssetFilter(values,Set.of());values.put(ManualAssetFilter.Field.BRAND,"changed");assertEquals("original",filter.values().get(ManualAssetFilter.Field.BRAND));assertThrows(UnsupportedOperationException.class,()->filter.values().clear());assertTrue(ManualAssetFilter.fromQuery(Map.of()).values().isEmpty());}
}
