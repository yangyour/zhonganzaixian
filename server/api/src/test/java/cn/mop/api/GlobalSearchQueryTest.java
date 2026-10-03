package cn.mop.api;
import cn.mop.domain.DomainException;
import cn.mop.domain.search.*;
import cn.mop.domain.search.GlobalSearchRepository.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class GlobalSearchQueryTest {
    @Test void contractGroupLimitsAreFiveFourThreeThree(){assertEquals(5,GlobalSearchQuery.ASSET_LIMIT);assertEquals(4,GlobalSearchQuery.EVENT_LIMIT);assertEquals(3,GlobalSearchQuery.TICKET_LIMIT);assertEquals(3,GlobalSearchQuery.AUTOMATION_LIMIT);}
    @Test void fullUnicodeQueryCountsCodePointsAndPreservesOriginalLiteral(){assertEquals("😀".repeat(100),new GlobalSearchQuery("😀".repeat(100)).text());assertEquals(" Ä%_ ",new GlobalSearchQuery(" Ä%_ ").text());assertThrows(DomainException.class,()->new GlobalSearchQuery("😀".repeat(101)));}
    @Test void emptyWhitespaceAndInvalidUnicodeNeverBecomeBrowseAll(){for(String value:Arrays.asList(null,""," \ufeff\u3000","x\n","x\u0085","x\ud800"))assertThrows(DomainException.class,()->new GlobalSearchQuery(value));}
    @Test void sqlSyntaxRemainsLiteralInput(){String query="x' OR '1'='1";assertEquals(query,new GlobalSearchQuery(query).text());}
    @Test void snapshotListsCannotBeMutatedByCaller(){var rows=new ArrayList<AssetHit>();rows.add(asset("1","tenant"));var snapshot=new Snapshot("x","tenant",Instant.EPOCH,"CURRENT_TENANT_INTERNAL",rows,false,List.of(),false);rows.clear();assertEquals(1,snapshot.assets().size());assertThrows(UnsupportedOperationException.class,()->snapshot.assets().clear());}
    @Test void scopeDuplicateAndOverLimitSnapshotsFailClosed(){assertThrows(IllegalArgumentException.class,()->new Snapshot("x","tenant",Instant.EPOCH,"CURRENT_TENANT_INTERNAL",List.of(asset("1","foreign")),false,List.of(),false));assertThrows(IllegalArgumentException.class,()->new Snapshot("x","tenant",Instant.EPOCH,"CURRENT_TENANT_INTERNAL",List.of(asset("1","tenant"),asset("1","tenant")),false,List.of(),false));var rows=new ArrayList<AssetHit>();for(int i=0;i<6;i++)rows.add(asset(""+i,"tenant"));assertThrows(IllegalArgumentException.class,()->new Snapshot("x","tenant",Instant.EPOCH,"CURRENT_TENANT_INTERNAL",rows,false,List.of(),false));}
    @Test void unknownVisibilityAndFalseOverflowAreRejected(){assertThrows(IllegalArgumentException.class,()->new Snapshot("x","tenant",Instant.EPOCH,"GLOBAL",List.of(),false,List.of(),false));assertThrows(IllegalArgumentException.class,()->new Snapshot("x","tenant",Instant.EPOCH,"OWN_REPORT_REGISTRATIONS",List.of(),true,List.of(),false));}
    @Test void reportHitsCannotClaimFormalWorkflowOrInvalidVersion(){assertThrows(IllegalArgumentException.class,()->new TicketHit("1","t","title","receipt","customer_report","CLOSED",1));assertThrows(IllegalArgumentException.class,()->new TicketHit("1","t","title","receipt","itsm","REGISTERED",1));assertThrows(IllegalArgumentException.class,()->new TicketHit("1","t","title","receipt","customer_report","REGISTERED",0));}
    AssetHit asset(String id,String tenant){return new AssetHit(id,tenant,"synthetic","router","manual",1);}
}
