package cn.mop.domain.reporting;

import cn.mop.domain.DomainException;
import java.time.Instant;
import java.util.*;

/** Complete bounded report-revision population; a follow-up candidate is not a sent reminder. */
public final class ReportFollowupWorklist {
    private ReportFollowupWorklist() {}
    public static final int DOCUMENT_LIMIT = 1000, RECIPIENT_LIMIT = 50000, PAGE_SIZE = 20;
    public record Cursor(Instant publishedAt, UUID documentId) {
        public Cursor { if (publishedAt == null || documentId == null || publishedAt.getNano()%1000000!=0) throw invalid(); }
    }
    public record Query(Instant from, Instant until, ReportReadership.Filter filter, Cursor before) {
        public Query {
            if(from==null||until==null||!from.isBefore(until)||from.getNano()%1000000!=0||until.getNano()%1000000!=0||filter==null)throw invalid();
        }
    }
    public record Row(UUID documentId, String label, String documentSha256, long stateVersion,
                      Instant capturedAt, Instant publishedAt, int totalRecipients, int readCount,
                      int unreadCount, int followupDueCount) {}
    public record Page(UUID tenantId, Instant observedAt, Query query, int scannedDocuments,
                       int publishedReports, int unreadReports, int followupDueReports, int matchingReports,
                       int totalRecipients, int readCount, int unreadCount, int followupDueCount,
                       List<Row> items, Cursor next) {
        public Page { items=List.copyOf(items); }
    }
    public static Row row(String label, ReportPublication.State state, ReportReadership.Page readership) {
        if(label==null||label.isBlank()||label.length()>500||!state.document().id().equals(readership.documentId())
            ||!state.document().tenantId().equals(readership.tenantId())||state.version()!=readership.stateVersion()
            ||!state.document().documentSha256().equals(readership.documentSha256()))throw unavailable();
        return new Row(state.document().id(),label,state.document().documentSha256(),state.version(),state.document().capturedAt(),
            state.publishedAt(),readership.totalRecipients(),readership.readCount(),readership.unreadCount(),readership.followupDueCount());
    }
    public static Page page(UUID tenant, Instant observed, Query query, int scanned, List<Row> rows) {
        if(tenant==null||observed==null||query==null||scanned<rows.size()||scanned<0)throw unavailable();
        if(scanned>DOCUMENT_LIMIT)throw limit();
        var ids=new HashSet<UUID>(); int recipients=0,read=0,unread=0,due=0,unreadReports=0,dueReports=0;
        var matches=new ArrayList<Row>();
        for(var r:rows){
            if(r==null||r.documentId()==null||!ids.add(r.documentId())||r.label()==null||r.label().isBlank()
                ||r.documentSha256()==null||!r.documentSha256().matches("[a-f0-9]{64}")||r.stateVersion()<1
                ||r.capturedAt()==null||r.capturedAt().isBefore(query.from())||!r.capturedAt().isBefore(query.until())
                ||r.publishedAt()==null||r.publishedAt().isBefore(r.capturedAt())||r.publishedAt().isAfter(observed)
                ||r.totalRecipients()<1||r.totalRecipients()>1000||r.readCount()<0||r.unreadCount()<0||r.followupDueCount()<0
                ||r.readCount()+r.unreadCount()!=r.totalRecipients()||r.followupDueCount()>r.unreadCount())throw unavailable();
            recipients+=r.totalRecipients();read+=r.readCount();unread+=r.unreadCount();due+=r.followupDueCount();
            if(recipients>RECIPIENT_LIMIT)throw limit();
            if(r.unreadCount()>0)unreadReports++;if(r.followupDueCount()>0)dueReports++;
            if(query.filter()==ReportReadership.Filter.ALL||query.filter()==ReportReadership.Filter.UNREAD&&r.unreadCount()>0
                ||query.filter()==ReportReadership.Filter.FOLLOWUP_DUE&&r.followupDueCount()>0)matches.add(r);
        }
        matches.sort(Comparator.comparing(Row::publishedAt).thenComparing(r->r.documentId().toString()).reversed());
        var remaining=matches.stream().filter(r->query.before()==null||r.publishedAt().isBefore(query.before().publishedAt())
            ||r.publishedAt().equals(query.before().publishedAt())&&r.documentId().toString().compareTo(query.before().documentId().toString())<0).limit(PAGE_SIZE+1).toList();
        var visible=remaining.stream().limit(PAGE_SIZE).toList(); Cursor next=null;
        if(remaining.size()>PAGE_SIZE){var last=visible.get(PAGE_SIZE-1);next=new Cursor(last.publishedAt(),last.documentId());}
        return new Page(tenant,observed,query,scanned,rows.size(),unreadReports,dueReports,matches.size(),recipients,read,unread,due,visible,next);
    }
    public static DomainException limit(){return new DomainException(422,"REPORT_FOLLOWUP_SCAN_LIMIT","报告跟进完整扫描超过技术预算，请缩小冻结时间范围；未返回部分统计");}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","报告跟进需要合法UTC毫秒范围、筛选及成对游标");}
    private static DomainException unavailable(){return new DomainException(503,"REPORT_PUBLICATION_UNAVAILABLE","报告跟进范围、时间或完整计数未通过核对");}
}
