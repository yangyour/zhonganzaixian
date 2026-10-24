package cn.mop.domain.notification;
import java.time.Instant;
import java.util.*;
/** Current-authority read models of unverified V50 declarations. Never provider lookup, send or retry permission. */
public interface DeliveryJournalReader {
 record Access(UUID tenantId,UUID actorId,long permissionVersion){public Access{Objects.requireNonNull(tenantId);Objects.requireNonNull(actorId);if(permissionVersion<1||permissionVersion>9007199254740991L)throw new IllegalArgumentException("Invalid receipt read scope");}}
 record Cursor(Instant firstRecordedAt,UUID deliveryId){public Cursor{Objects.requireNonNull(firstRecordedAt);Objects.requireNonNull(deliveryId);if(firstRecordedAt.getNano()%1000000!=0)throw new IllegalArgumentException("Invalid receipt cursor");}}
 record Entry(UUID tenantId,UUID deliveryId,UUID resourcePoolId,UUID eventId,String channel,UUID receiverId,String stage,long version,String recordedStatus,Instant queuedAt,Instant observedAt,Instant firstRecordedAt,int attemptCount,int receiptCount,UUID lastRequestId,UUID lastStartCommandId,String headSha256){public Entry{
  for(var id:List.of(tenantId,deliveryId,resourcePoolId,eventId,receiverId))Objects.requireNonNull(id);DeliveryReceiptState.Channel.valueOf(channel);DeliveryReceiptState.Status.valueOf(recordedStatus);Objects.requireNonNull(queuedAt);Objects.requireNonNull(observedAt);Objects.requireNonNull(firstRecordedAt);
  if(stage==null||!stage.matches("[A-Z][A-Z0-9_]{0,63}")||version<0||version>12000||attemptCount<0||attemptCount>1000||receiptCount<0||receiptCount>10000||firstRecordedAt.getNano()%1000000!=0||headSha256==null||!headSha256.matches("[a-f0-9]{64}")||attemptCount==0&&(lastRequestId!=null||lastStartCommandId!=null)||attemptCount>0&&(lastRequestId==null||lastStartCommandId==null))throw new IllegalArgumentException("Invalid journal entry projection");
 }}
 record Step(long version,UUID commandId,String kind,UUID requestId,UUID receiptId,String recordedReceiptStatus,Instant occurredAt,Instant observedAt,Instant recordedAt){public Step{
  Objects.requireNonNull(commandId);Objects.requireNonNull(occurredAt);Objects.requireNonNull(observedAt);Objects.requireNonNull(recordedAt);
  if(version<0||version>12000||!Set.of("QUEUE","START","DEAD_LETTER","RECEIPT").contains(kind)||(version==0)!=kind.equals("QUEUE")||recordedAt.getNano()%1000000!=0||occurredAt.isAfter(observedAt))throw new IllegalArgumentException("Invalid journal history projection");
  if(kind.equals("START")?(requestId==null||receiptId!=null||recordedReceiptStatus!=null):kind.equals("RECEIPT")?(requestId==null||receiptId==null||!Set.of("ACCEPTED","FAILED","DELIVERED").contains(recordedReceiptStatus)):(requestId!=null||receiptId!=null||recordedReceiptStatus!=null))throw new IllegalArgumentException("Invalid original command projection");
 }}
 record Page(Access access,Instant observedAt,int pageSize,long totalCount,List<Entry> items,Cursor next){public Page{items=List.copyOf(items);if(pageSize<1||pageSize>20||totalCount<0||totalCount>9007199254740991L||items.size()>pageSize||items.size()>totalCount)throw new IllegalArgumentException("Invalid receipt page");
  Objects.requireNonNull(access);Objects.requireNonNull(observedAt);var ids=new HashSet<UUID>();Entry previous=null;for(var item:items){if(!access.tenantId().equals(item.tenantId())||!ids.add(item.deliveryId())||previous!=null&&(item.firstRecordedAt().isAfter(previous.firstRecordedAt())||item.firstRecordedAt().equals(previous.firstRecordedAt())&&item.deliveryId().toString().compareTo(previous.deliveryId().toString())>=0))throw new IllegalArgumentException("Invalid journal page scope/order");previous=item;}
  if(next!=null&&(items.size()!=pageSize||!next.deliveryId().equals(previous.deliveryId())||!next.firstRecordedAt().equals(previous.firstRecordedAt())))throw new IllegalArgumentException("Invalid journal page cursor");
 }}
 record Detail(Access access,Instant observedAt,Entry entry,List<Step> history,Long nextBeforeVersion){public Detail{history=List.copyOf(history);}}
 record Recovery(Access access,Instant observedAt,Entry current,Step original){}
 Page list(Access access,UUID pool,Cursor before,int size);
    /** Filters verified current heads across the entire bounded authorized population, never just the visible page. */
    enum AttentionStatus {
        ATTENTION,
        UNKNOWN,
        FAILED,
        DEAD_LETTER
    }

    /** The complete scanned population is bounded independently of the requested visible page size. */
    record AttentionPage(Page page, AttentionStatus status, int scannedRoots) {
        public AttentionPage {
            Objects.requireNonNull(page);
            Objects.requireNonNull(status);
            if (scannedRoots < 0 || scannedRoots > 1000 || page.totalCount() > scannedRoots) {
                throw new IllegalArgumentException("Invalid complete attention population");
            }
        }
    }

    AttentionPage attention(Access access, UUID pool, AttentionStatus status, Cursor before, int size);
 Detail detail(Access access,UUID deliveryId,Long beforeVersion,int size);
 Recovery recover(Access access,UUID deliveryId,UUID commandId);
}
