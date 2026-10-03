package cn.mop.domain.search;
import java.time.Instant;
import java.util.*;
/** Minimal current-tenant names/receipts only. Unsupported sources must stay unavailable, not a fabricated empty set. */
public interface GlobalSearchRepository {
    record Access(String actorId,String tenantId,long permissionVersion) {}
    record AssetHit(String id,String tenantId,String name,String type,String source,long version) {public AssetHit{if(id==null||tenantId==null||name==null||type==null||source==null||version<1)throw new IllegalArgumentException("Invalid asset hit");}}
    record TicketHit(String id,String tenantId,String title,String receiptNo,String source,String registrationStatus,long version) {public TicketHit{if(id==null||tenantId==null||title==null||receiptNo==null||!"customer_report".equals(source)||!"REGISTERED".equals(registrationStatus)||version<1)throw new IllegalArgumentException("Invalid report registration hit");}}
    record Snapshot(String query,String tenantId,Instant observedAt,String ticketVisibility,List<AssetHit> assets,boolean moreAssets,List<TicketHit> tickets,boolean moreTickets) {
        public Snapshot {Objects.requireNonNull(query);Objects.requireNonNull(tenantId);Objects.requireNonNull(observedAt);if(!Set.of("OWN_REPORT_REGISTRATIONS","CURRENT_TENANT_INTERNAL").contains(ticketVisibility))throw new IllegalArgumentException("Unknown search visibility");assets=List.copyOf(assets);tickets=List.copyOf(tickets);if(assets.size()>GlobalSearchQuery.ASSET_LIMIT||tickets.size()>GlobalSearchQuery.TICKET_LIMIT||moreAssets&&assets.size()!=GlobalSearchQuery.ASSET_LIMIT||moreTickets&&tickets.size()!=GlobalSearchQuery.TICKET_LIMIT||assets.stream().map(AssetHit::id).distinct().count()!=assets.size()||tickets.stream().map(TicketHit::id).distinct().count()!=tickets.size()||assets.stream().anyMatch(a->!a.tenantId().equals(tenantId))||tickets.stream().anyMatch(t->!t.tenantId().equals(tenantId)))throw new IllegalArgumentException("Search scope or bound mismatch");}
    }
    Snapshot search(Access access,GlobalSearchQuery query);
}
