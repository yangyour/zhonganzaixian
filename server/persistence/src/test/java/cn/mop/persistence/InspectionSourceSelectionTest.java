package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.inspection.InspectionWorksheetRepository.Selection;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class InspectionSourceSelectionTest {
    @Test void canonicalUuidMakesCaseVariantsSameSelection(){String id=UUID.randomUUID().toString();assertEquals(new Selection(id,1),new Selection(id.toUpperCase(),1));}
    @Test void rejectsNullAndNonCanonicalUuidShapes(){for(String id:new String[]{null,"","1-1-1-1-1","not-an-id"})assertEquals(400,assertThrows(DomainException.class,()->new Selection(id,1)).status);}
    @Test void boundsSafeExplicitVersion(){String id=UUID.randomUUID().toString();for(long version:new long[]{Long.MIN_VALUE,0,9_007_199_254_740_991L,Long.MAX_VALUE})assertEquals(400,assertThrows(DomainException.class,()->new Selection(id,version)).status);assertEquals(9_007_199_254_740_990L,new Selection(id,9_007_199_254_740_990L).version());}
}
