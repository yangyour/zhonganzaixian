package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.assetmetadata.*;
import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ManualWarrantyUpdateTest {
    private static Asset asset(){return new Asset("55000000-0000-4000-8000-000000000099","55000000-0000-4000-8000-000000000001","synthetic","router","192.0.2.1","site-a-main","unknown","DRAFT","manual",null,null,2,Instant.EPOCH,Instant.EPOCH,"UNKNOWN","PENDING_ONBOARDING");}
    private static ManualAssetMetadata facts(){return new ManualAssetMetadata("synthetic","router","site-a-main","brand","model","serial","location",null,"business",LocalDate.of(2020,1,1),null,null,"collection","original note");}
    @Test void existingFieldsRemainExactWhileDatesAndNoteAreExplicitlyChanged(){var update=new ManualWarrantyUpdate(new ManualWarrantyDetails(LocalDate.of(2026,1,1),"supplier"),LocalDate.of(2027,1,1),LocalDate.of(2030,1,1),"new note");var m=update.applyTo(asset(),facts());assertEquals(new ManualAssetMetadata("synthetic","router","site-a-main","brand","model","serial","location",null,"business",LocalDate.of(2020,1,1),update.warrantyExpiry(),update.overdueServiceDate(),"collection","new note"),m);}
    @Test void absentMetadataUsesActualAssetIdentityAndNoOptionalDefaults(){var m=new ManualWarrantyUpdate(new ManualWarrantyDetails(null,null),null,null,"explicit note").applyTo(asset(),null);assertEquals(asset().name(),m.name());assertEquals(asset().type(),m.type());assertEquals(asset().siteId(),m.siteId());assertNull(m.brand());assertNull(m.ownerId());assertNull(m.purchaseDate());assertNull(m.collectionMethodNote());assertNull(m.warrantyExpiry());}
    @Test void nullWrapperBadOrderBadNoteAndDateFail(){assertThrows(DomainException.class,()->new ManualWarrantyUpdate(null,null,null,"note"));assertThrows(DomainException.class,()->new ManualWarrantyUpdate(new ManualWarrantyDetails(LocalDate.of(2027,1,1),null),LocalDate.of(2026,1,1),null,"note"));for(String note:Arrays.asList(null,""," ","x\u0001y","😀".repeat(2001)))assertThrows(DomainException.class,()->new ManualWarrantyUpdate(new ManualWarrantyDetails(null,null),null,null,note));assertThrows(DomainException.class,()->new ManualWarrantyUpdate(new ManualWarrantyDetails(null,null),null,LocalDate.of(10000,1,1),"note"));}
    @Test void oldStoredThreeFieldSnapshotDeserializesWithoutNewFactsAndLegacyDtoStaysStable()throws Exception{
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var old=mapper.valueToTree(new Snapshot(asset(),facts(),2L));((com.fasterxml.jackson.databind.node.ObjectNode)old).remove("warranty_details");var read=mapper.treeToValue(old,Snapshot.class);assertNull(read.warrantyDetails());assertEquals(facts(),read.metadata());
        var modern=new Snapshot(asset(),facts(),2L,new ManualWarrantyDetails(null,"supplier"));assertEquals(Set.of("asset","metadata","metadata_version"),ManualAssetMetadataController.snapshotDto(modern).keySet());assertEquals(modern,mapper.readValue(mapper.writeValueAsBytes(modern),Snapshot.class));
    }
    @Test void snapshotCannotClaimWarrantyWithoutMetadataOrContradictItsExpiry(){assertThrows(IllegalArgumentException.class,()->new Snapshot(asset(),null,null,new ManualWarrantyDetails(null,null)));var f=facts();var dated=new ManualAssetMetadata(f.name(),f.type(),f.siteId(),null,null,null,null,null,null,null,LocalDate.of(2026,1,1),null,null,"note");assertThrows(DomainException.class,()->new Snapshot(asset(),dated,2L,new ManualWarrantyDetails(LocalDate.of(2027,1,1),null)));}
}
