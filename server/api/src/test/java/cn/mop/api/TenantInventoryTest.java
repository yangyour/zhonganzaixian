package cn.mop.api;
import cn.mop.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class TenantInventoryTest {
    @Test void boundedQueryAllowsExplicitUuidAndOneLookaheadRow(){assertNull(new TenantInventory.Query(null,1).afterId());assertEquals(101,new TenantInventory.Query(UUID.randomUUID().toString(),101).limit());}
    @Test void emptyMalformedOrSqlLikeCursorsAreRejected(){for(String s:new String[]{"","1-1-1-1-1","not-id","' OR true --","AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"})assertThrows(DomainException.class,()->new TenantInventory.Query(s,15));}
    @Test void technicalQueryLimitIsNotUnbounded(){for(int limit:new int[]{-1,0,102,Integer.MAX_VALUE})assertThrows(DomainException.class,()->new TenantInventory.Query(null,limit));}
    @Test void inventoryPreservesExactLongCountsWithoutCalculatingQuotaOrStatus(){var r=new TenantInventory(UUID.randomUUID().toString(),"明确记录",Instant.EPOCH,Long.MAX_VALUE,0,1);assertEquals(Long.MAX_VALUE,r.registeredAssetCount());assertEquals(0,r.registeredSiteCount());}
    @Test void invalidKnownFactsCannotBecomeZeroOrAnInventedTimestamp(){String id=UUID.randomUUID().toString();assertThrows(IllegalArgumentException.class,()->new TenantInventory(id,"x",null,0,0,0));assertThrows(IllegalArgumentException.class,()->new TenantInventory(id," ",Instant.EPOCH,0,0,0));assertThrows(IllegalArgumentException.class,()->new TenantInventory(id,"x",Instant.EPOCH,-1,0,0));assertThrows(IllegalArgumentException.class,()->new TenantInventory(id,"x",Instant.EPOCH,0,-1,0));assertThrows(IllegalArgumentException.class,()->new TenantInventory(id,"x",Instant.EPOCH,0,0,-1));}
    @Test void uuidValidationRejectsMissingOrAbbreviatedValues(){assertFalse(TenantInventory.canonicalUuid(null));assertFalse(TenantInventory.canonicalUuid("1-1-1-1-1"));assertTrue(TenantInventory.canonicalUuid(UUID.randomUUID().toString()));}
}
