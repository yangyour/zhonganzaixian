package cn.mop.domain.backup;
import java.time.Instant;
import java.util.*;
/** Authorized recorded run facts only. No cross-run failure policy, ticketing, or device permission. */
public interface BackupRunHistoryReader {
 record Access(UUID tenantId,UUID actorId,long permissionVersion){public Access{Objects.requireNonNull(tenantId);Objects.requireNonNull(actorId);if(permissionVersion<1||permissionVersion>9007199254740991L)throw new IllegalArgumentException("Invalid backup history scope");}}
 record Cursor(Instant queuedAt,UUID runId){public Cursor{Objects.requireNonNull(queuedAt);Objects.requireNonNull(runId);if(queuedAt.getNano()%1000000!=0)throw new IllegalArgumentException("Invalid cursor time");}}
 record Entry(UUID runId,BackupRunState state,String snapshotSha256){public Entry{Objects.requireNonNull(runId);Objects.requireNonNull(state);if(snapshotSha256==null||!snapshotSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid snapshot fingerprint");}}
 record Page(Access access,UUID assetId,Instant observedAt,int pageSize,long totalCount,List<Entry> items,Cursor next){public Page{
  Objects.requireNonNull(access);Objects.requireNonNull(assetId);Objects.requireNonNull(observedAt);items=List.copyOf(items);
  if(pageSize<1||pageSize>50||totalCount<0||totalCount>9007199254740991L||items.size()>pageSize||items.size()>totalCount)throw new IllegalArgumentException("Invalid history page");
  var ids=new HashSet<UUID>();Entry prior=null;for(var item:items){if(!ids.add(item.runId())||!item.state().tenantId().equals(access.tenantId())||!item.state().assetId().equals(assetId)||prior!=null&&(item.state().queuedAt().isAfter(prior.state().queuedAt())||item.state().queuedAt().equals(prior.state().queuedAt())&&item.runId().toString().compareTo(prior.runId().toString())>=0))throw new IllegalArgumentException("Invalid history population/order");prior=item;}
  if(next!=null&&(items.size()!=pageSize||!next.runId().equals(prior.runId())||!next.queuedAt().equals(prior.state().queuedAt())))throw new IllegalArgumentException("Invalid history cursor");
 }}
 Page read(Access access,UUID assetId,Cursor before,int pageSize);
}
