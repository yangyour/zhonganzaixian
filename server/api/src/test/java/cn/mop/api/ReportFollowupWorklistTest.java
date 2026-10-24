package cn.mop.api;
import cn.mop.domain.DomainException;
import cn.mop.domain.reporting.*;
import cn.mop.domain.reporting.ReportFollowupWorklist.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReportFollowupWorklistTest {
 static final UUID TENANT=UUID.fromString("79000000-0000-4000-8000-000000000001");
 static final Instant FROM=Instant.parse("2026-10-01T00:00:00Z"),UNTIL=Instant.parse("2026-11-01T00:00:00Z"),NOW=Instant.parse("2026-10-24T00:00:00Z");
 static UUID id(int n){return UUID.fromString("79000000-0000-4000-8000-"+String.format("%012d",n));}
 static Row row(int n,int unread,int due){return new Row(id(n),"Synthetic "+n,"a".repeat(64),5,FROM,FROM.plusSeconds(60),3,3-unread,unread,due);}
 static Query query(ReportReadership.Filter filter,Cursor before){return new Query(FROM,UNTIL,filter,before);}
 @Test void filtersCompletePopulationBeforePaginationAndKeepsTotals(){
  var rows=new ArrayList<Row>();for(int n=1;n<=65;n++)rows.add(row(n,n%2==0?2:0,n%4==0?1:0));
  var first=ReportFollowupWorklist.page(TENANT,NOW,query(ReportReadership.Filter.UNREAD,null),70,rows);
  assertEquals(32,first.matchingReports());assertEquals(65,first.publishedReports());assertEquals(70,first.scannedDocuments());assertEquals(64,first.unreadCount());assertEquals(16,first.followupDueCount());assertEquals(20,first.items().size());assertEquals(id(64),first.items().get(0).documentId());
  var next=ReportFollowupWorklist.page(TENANT,NOW,query(ReportReadership.Filter.UNREAD,first.next()),70,rows);
  assertEquals(12,next.items().size());assertNull(next.next());assertEquals(first.unreadCount(),next.unreadCount());
  var unique=new HashSet<UUID>();first.items().forEach(r->assertTrue(unique.add(r.documentId())));next.items().forEach(r->assertTrue(unique.add(r.documentId())));assertEquals(32,unique.size());
 }
 @Test void emptyAndNonmatchingAreVerifiedNotPartial(){
  var empty=ReportFollowupWorklist.page(TENANT,NOW,query(ReportReadership.Filter.FOLLOWUP_DUE,null),0,List.of());assertEquals(0,empty.matchingReports());assertNull(empty.next());
  var noDue=ReportFollowupWorklist.page(TENANT,NOW,query(ReportReadership.Filter.FOLLOWUP_DUE,null),2,List.of(row(1,1,0)));assertEquals(1,noDue.unreadCount());assertTrue(noDue.items().isEmpty());
 }
 @Test void limitsRejectEntireScan(){
  assertEquals(422,assertThrows(DomainException.class,()->ReportFollowupWorklist.page(TENANT,NOW,query(ReportReadership.Filter.ALL,null),1001,List.of())).status);
  var rows=new ArrayList<Row>();for(int i=1;i<=51;i++)rows.add(new Row(id(i),"Synthetic","a".repeat(64),5,FROM,FROM,1000,0,1000,1000));
  assertEquals(422,assertThrows(DomainException.class,()->ReportFollowupWorklist.page(TENANT,NOW,query(ReportReadership.Filter.ALL,null),51,rows)).status);
 }
 @Test void duplicateWrongWindowBadCountsAndFutureRowsFailClosed(){
  for(var rows:List.of(List.of(row(1,1,0),row(1,1,0)),List.of(row(1,4,0)),List.of(row(1,1,2)),
   List.of(new Row(id(1),"Synthetic","a".repeat(64),5,UNTIL,UNTIL,1,0,1,0))))
   assertEquals(503,assertThrows(DomainException.class,()->ReportFollowupWorklist.page(TENANT,NOW,query(ReportReadership.Filter.ALL,null),rows.size(),rows)).status);
 }
 @Test void exactSevenDayBoundaryReusesPublishedRevisionRule(){
  var state=ReportPublicationTest.published();var at=state.publishedAt().plusSeconds(604800);
  for(int ms:List.of(0,1)){
   var p=ReportReadership.page(state,List.of(),Map.of(),at.plusMillis(ms),ReportReadership.Filter.ALL,null);
   var r=ReportFollowupWorklist.row("Synthetic",state,p);assertEquals(ms,r.followupDueCount());
  }
 }
 @Test void queryRejectsEmptyReversedAndSubmillisecondWindows(){
  assertThrows(DomainException.class,()->new Query(FROM,FROM,ReportReadership.Filter.ALL,null));
  assertThrows(DomainException.class,()->new Query(UNTIL,FROM,ReportReadership.Filter.ALL,null));
  assertThrows(DomainException.class,()->new Query(FROM.plusNanos(1),UNTIL,ReportReadership.Filter.ALL,null));
 }
}
