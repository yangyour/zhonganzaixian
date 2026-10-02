package cn.mop.api;
import cn.mop.domain.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AssetReadQueryTest {
    static final String ID="55000000-0000-4000-8000-000000000001";
    @Test void preservesLegacyLiteralFiltersAndEmptyStringSemantics(){var q=new AssetReadQuery("router","unknown",""," %_ ",101,null,null);assertEquals("",q.siteId());assertEquals(" %_ ",q.query());assertDoesNotThrow(()->new AssetReadQuery(null,null,null,"",1,null,null));}
    @Test void boundsLimitAndRequiresPairedCreationCursor(){for(int limit:new int[]{0,102})assertThrows(DomainException.class,()->new AssetReadQuery(null,null,null,null,limit,null,null));assertThrows(DomainException.class,()->new AssetReadQuery(null,null,null,null,10,Instant.EPOCH,null));assertThrows(DomainException.class,()->new AssetReadQuery(null,null,null,null,10,null,ID));assertDoesNotThrow(()->new AssetReadQuery(null,null,null,null,10,Instant.EPOCH,ID));}
    @Test void rejectsUnknownFiltersMalformedUnicodeAndOutOfRangeTimes(){assertThrows(DomainException.class,()->new AssetReadQuery("unknown",null,null,null,10,null,null));assertThrows(DomainException.class,()->new AssetReadQuery(null,"healthy",null,null,10,null,null));for(String query:new String[]{"a".repeat(101),"x\u0000","x\ud800"})assertThrows(DomainException.class,()->new AssetReadQuery(null,null,null,query,10,null,null));assertThrows(DomainException.class,()->new AssetReadQuery(null,null,null,null,10,Instant.EPOCH.plusNanos(1),ID));assertThrows(DomainException.class,()->new AssetReadQuery(null,null,null,null,10,Instant.parse("0000-01-01T00:00:00Z"),ID));}
}
