package cn.mop.domain.operations;
import java.time.*;
import java.util.*;
import java.math.*;
/** One current authorized event projection; not a frozen monthly result or verified collection heartbeat. */
public interface EventOperationalMetricsReader {
 long MAX_SAFE=9007199254740991L;
 enum Window{HOURS_24(24),DAYS_7(168),DAYS_30(720);public final int hours;Window(int hours){this.hours=hours;}}
 enum View{CURRENT_PROBLEMS,RECENT_PROBLEMS}
 enum Severity{DISASTER,HIGH,AVERAGE,WARNING,INFO}
 record Access(String actorId,String tenantId,long permissionVersion){}
 record Cursor(Instant problemAt,UUID id){public Cursor{Objects.requireNonNull(problemAt);Objects.requireNonNull(id);}}
 record Sample(UUID id,UUID assetId,long version,String ruleName,Severity severity,String monitorState,String handlingState,String suppressionState,Instant problemAt,Instant firstAckAt,Instant updatedAt){
  public Sample{Objects.requireNonNull(id);Objects.requireNonNull(assetId);Objects.requireNonNull(severity);Objects.requireNonNull(problemAt);Objects.requireNonNull(updatedAt);if(!validTime(problemAt)||!validTime(updatedAt)||firstAckAt!=null&&!validTime(firstAckAt))throw new IllegalArgumentException("Invalid event timestamp");if(version<1||version>MAX_SAFE||ruleName==null||ruleName.isBlank()||ruleName.length()>128||!Set.of("PROBLEM","RECOVERED").contains(monitorState)||!Set.of("NEW","ACKNOWLEDGED","IN_PROGRESS","PENDING_REVIEW","RESOLVED","CLOSED").contains(handlingState)||!Set.of("NONE","FLAP_SILENCED","DEPENDENCY_SUPPRESSED","MAINTENANCE_SILENCED","INFO_DEFAULT_SILENT").contains(suppressionState))throw new IllegalArgumentException("Invalid event source");}
  public String acknowledgementState(Instant observed){if(firstAckAt==null)return handlingState.equals("NEW")?"UNACKNOWLEDGED":"MISSING_ACK_TIME";return firstAckAt.isBefore(problemAt)||firstAckAt.isAfter(observed)?"INVALID_ACK_TIME":"RECORDED_ACK_TIME";}
  public BigInteger elapsedMillis(Instant observed){return acknowledgementState(observed).equals("RECORDED_ACK_TIME")?BigInteger.valueOf(Duration.between(problemAt,firstAckAt).toMillis()):null;}
 }
 record Mtta(String status,long populationCount,Long sampleCount,Long unacknowledgedCount,Long invalidCount,OperationalLatencyStatistics.Result statistics,String sourceManifestSha256){
  public Mtta{if(populationCount<0||populationCount>MAX_SAFE||!Set.of("CALCULATED","NO_ACKNOWLEDGED_SAMPLES","INVALID_SOURCE_TIMESTAMPS","SAMPLE_BUDGET_EXCEEDED").contains(status))throw new IllegalArgumentException("Invalid MTTA projection");
   if(status.equals("SAMPLE_BUDGET_EXCEEDED")){if(populationCount<=OperationalLatencyStatistics.MAX_SAMPLES||sampleCount!=null||unacknowledgedCount!=null||invalidCount!=null||statistics!=null||sourceManifestSha256!=null)throw new IllegalArgumentException("Invalid budget boundary");}
   else {if(sampleCount==null||unacknowledgedCount==null||invalidCount==null||sampleCount<0||unacknowledgedCount<0||invalidCount<0||sampleCount+unacknowledgedCount+invalidCount!=populationCount||sourceManifestSha256==null||!sourceManifestSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid MTTA population");
    if(status.equals("CALCULATED")&&(sampleCount<1||invalidCount!=0||statistics==null||statistics.sampleCount()!=sampleCount)||status.equals("NO_ACKNOWLEDGED_SAMPLES")&&(sampleCount!=0||invalidCount!=0||statistics==null||statistics.sampleCount()!=0)||status.equals("INVALID_SOURCE_TIMESTAMPS")&&(invalidCount<1||statistics!=null))throw new IllegalArgumentException("Invalid MTTA state");
   }
  }
 }
 record Snapshot(UUID tenantId,UUID actorId,long permissionVersion,Instant observedAt,Window window,View view,Instant from,Map<Severity,Long> currentProblems,long currentProblemCount,Mtta mtta,List<Sample> items,Cursor next){
  public Snapshot{Objects.requireNonNull(tenantId);Objects.requireNonNull(actorId);Objects.requireNonNull(observedAt);Objects.requireNonNull(window);Objects.requireNonNull(view);Objects.requireNonNull(from);Objects.requireNonNull(mtta);currentProblems=Map.copyOf(currentProblems);items=List.copyOf(items);
   if(!validTime(observedAt)||!validTime(from)||permissionVersion<1||permissionVersion>MAX_SAFE||currentProblemCount<0||currentProblemCount>MAX_SAFE||!from.equals(observedAt.minus(Duration.ofHours(window.hours)))||currentProblems.size()!=5||currentProblems.values().stream().anyMatch(n->n==null||n<0||n>MAX_SAFE)||currentProblems.values().stream().map(BigInteger::valueOf).reduce(BigInteger.ZERO,BigInteger::add).compareTo(BigInteger.valueOf(currentProblemCount))!=0||items.size()>50)throw new IllegalArgumentException("Invalid event metric snapshot");
   var ids=new HashSet<UUID>();Sample previous=null;for(var item:items){if(!ids.add(item.id())||previous!=null&&(item.problemAt().isAfter(previous.problemAt())||item.problemAt().equals(previous.problemAt())&&item.id().toString().compareTo(previous.id().toString())>=0))throw new IllegalArgumentException("Invalid source page order");
    if(view==View.CURRENT_PROBLEMS&&(!item.monitorState().equals("PROBLEM")||!item.suppressionState().equals("NONE"))||view==View.RECENT_PROBLEMS&&(item.problemAt().isBefore(from)||!item.problemAt().isBefore(observedAt)))throw new IllegalArgumentException("Invalid source page population");previous=item;}
   if(items.size()>(view==View.CURRENT_PROBLEMS?currentProblemCount:mtta.populationCount())||next!=null&&(items.size()!=50||!next.id().equals(items.get(49).id())||!next.problemAt().equals(items.get(49).problemAt())))throw new IllegalArgumentException("Invalid source pagination");
  }
 }
 private static boolean validTime(Instant t){return t!=null&&t.getNano()%1000000==0&&!t.isBefore(Instant.parse("0001-01-01T00:00:00Z"))&&!t.isAfter(Instant.parse("9999-12-31T23:59:59.999Z"));}
 Snapshot read(Access access,Window window,View view,Cursor before);
}
