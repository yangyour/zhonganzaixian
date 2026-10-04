package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.inspection.InspectionSourceCollectionRepository.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class InspectionSourceCollectionInputTest {
    static final String ID="ABCDEFAB-1234-4567-8901-ABCDEFABCDEF",HASH="a".repeat(64);
    @Test void normalizesExplicitIdsWithoutChangingVersionOrHash(){var s=new Source(ID,2,HASH);assertEquals(ID.toLowerCase(Locale.ROOT),s.worksheetId());assertEquals(2,s.version());assertEquals(HASH,s.expectedSourceSha256());}
    @Test void rejectsMissingMalformedOrNonCanonicalHashesAndVersions(){for(String h:Arrays.asList(null,"", "A".repeat(64),"a".repeat(63),"g".repeat(64)))assertEquals(400,assertThrows(DomainException.class,()->new Source(ID,1,h)).status);for(long v:new long[]{0,-1,Long.MAX_VALUE})assertEquals(400,assertThrows(DomainException.class,()->new Source(ID,v,HASH)).status);assertThrows(DomainException.class,()->new Source("bad",1,HASH));}
    @Test void copiesInputAndAllowsEmptyTechnicalDraft(){var values=new ArrayList<Source>();values.add(new Source(ID,1,HASH));var input=new Creation("人工来源草稿",values);values.clear();assertEquals(1,input.sources().size());assertThrows(UnsupportedOperationException.class,()->input.sources().clear());assertTrue(new Creation("空选择",List.of()).sources().isEmpty());}
    @Test void validatesLabelsAndNullSelections(){for(String label:Arrays.asList(null,"","  ","x\ny","x".repeat(201)))assertThrows(DomainException.class,()->new Creation(label,List.of()));assertThrows(DomainException.class,()->new Creation("valid",null));assertThrows(DomainException.class,()->new Creation("valid",Arrays.asList((Source)null)));new Creation("😀".repeat(200),List.of());}
    @Test void rejectsDuplicateVersionEvenWhenExpectedHashDiffers(){var s=new Source(ID,1,HASH);assertThrows(DomainException.class,()->new Creation("duplicates",List.of(s,new Source(ID.toLowerCase(),1,"b".repeat(64)))));assertEquals(2,new Creation("versions",List.of(s,new Source(ID,2,HASH))).sources().size());}
    @Test void enforcesTechnicalCountBeforeDuplicateCheck(){assertEquals(413,assertThrows(DomainException.class,()->new Creation("oversize",Collections.nCopies(1001,new Source(ID,1,HASH)))).status);}
}
