package cn.mop.domain.search;
import java.time.Instant;
import cn.mop.domain.monitoring.MonitoringEventPipeline.*;
import java.util.*;
/** Minimal current-tenant names/receipts only. Unsupported sources must stay unavailable, not a fabricated empty set. */
public interface GlobalSearchRepository {
    record Access(String actorId,String tenantId,long permissionVersion) {}
    record AssetHit(String id,String tenantId,String name,String type,String source,long version) {public AssetHit{if(id==null||tenantId==null||name==null||type==null||source==null||version<1)throw new IllegalArgumentException("Invalid asset hit");}}
    record TicketHit(String id,String tenantId,String title,String receiptNo,String source,String registrationStatus,long version) {public TicketHit{if(id==null||tenantId==null||title==null||receiptNo==null||!"customer_report".equals(source)||!"REGISTERED".equals(registrationStatus)||version<1)throw new IllegalArgumentException("Invalid report registration hit");}}
    /** Only searchable, non-private event summary fields; never reuse the full event detail projection. */
    record EventHit(String id,String tenantId,String ruleCode,String ruleName,long version,Severity severity,MonitorState monitorState,HandlingState handlingState,int occurrenceCount,Instant lastOccurredAt) {
        public EventHit {if(id==null||tenantId==null||ruleCode==null||ruleCode.isBlank()||ruleCode.codePointCount(0,ruleCode.length())>64||ruleName==null||ruleName.isBlank()||ruleName.codePointCount(0,ruleName.length())>128||version<1||version>9_007_199_254_740_991L||severity==null||monitorState==null||handlingState==null||occurrenceCount<1||lastOccurredAt==null)throw new IllegalArgumentException("Invalid event hit");}
    }
    record Snapshot(String query,String tenantId,Instant observedAt,String ticketVisibility,List<AssetHit> assets,boolean moreAssets,List<TicketHit> tickets,boolean moreTickets,boolean eventsAuthorized,List<EventHit> events,boolean moreEvents) {
        public Snapshot {Objects.requireNonNull(query);Objects.requireNonNull(tenantId);Objects.requireNonNull(observedAt);if(!Set.of("OWN_REPORT_REGISTRATIONS","CURRENT_TENANT_INTERNAL").contains(ticketVisibility))throw new IllegalArgumentException("Unknown search visibility");assets=List.copyOf(assets);tickets=List.copyOf(tickets);events=List.copyOf(events);if(eventsAuthorized!=ticketVisibility.equals("CURRENT_TENANT_INTERNAL")||events.size()>GlobalSearchQuery.EVENT_LIMIT||moreEvents&&events.size()!=GlobalSearchQuery.EVENT_LIMIT||!eventsAuthorized&&(!events.isEmpty()||moreEvents)||events.stream().map(EventHit::id).distinct().count()!=events.size()||events.stream().anyMatch(e->!e.tenantId().equals(tenantId))||assets.size()>GlobalSearchQuery.ASSET_LIMIT||tickets.size()>GlobalSearchQuery.TICKET_LIMIT||moreAssets&&assets.size()!=GlobalSearchQuery.ASSET_LIMIT||moreTickets&&tickets.size()!=GlobalSearchQuery.TICKET_LIMIT||assets.stream().map(AssetHit::id).distinct().count()!=assets.size()||tickets.stream().map(TicketHit::id).distinct().count()!=tickets.size()||assets.stream().anyMatch(a->!a.tenantId().equals(tenantId))||tickets.stream().anyMatch(t->!t.tenantId().equals(tenantId)))throw new IllegalArgumentException("Search scope or bound mismatch");}
    }
    Snapshot search(Access access,GlobalSearchQuery query);
}
