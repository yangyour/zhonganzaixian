package cn.mop.domain.operations;
import cn.mop.domain.operations.EventOperationalMetricsReader.*;
import java.time.*;
import java.util.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
/** One event-level MTTA definition: first recorded human acknowledgment minus original problem time. */
public final class EventOperationalMetrics {
 public static final String VERSION="mop-event-acknowledgement-v1";
 private EventOperationalMetrics(){}
 public static Mtta calculate(UUID tenant,Instant from,Instant to,long population,List<Sample> samples){
  if(population>OperationalLatencyStatistics.MAX_SAMPLES)return new Mtta("SAMPLE_BUDGET_EXCEEDED",population,null,null,null,null,null);
  if(samples==null||samples.size()!=population)throw new IllegalArgumentException("Complete cohort required, never a returned-page estimate");
  var durations=new ArrayList<BigInteger>();long unack=0,invalid=0;var ids=new HashSet<UUID>();StringBuilder manifest=new StringBuilder(VERSION+"\n"+tenant+"\n"+from+"\n"+to+"\n");
  for(var sample:samples.stream().sorted(Comparator.comparing(v->v.id().toString())).toList()){if(!ids.add(sample.id())||sample.problemAt().isBefore(from)||!sample.problemAt().isBefore(to))throw new IllegalArgumentException("Event cohort mismatch");
   var elapsed=sample.elapsedMillis(to);if(elapsed!=null)durations.add(elapsed);else if(sample.acknowledgementState(to).equals("UNACKNOWLEDGED"))unack++;else invalid++;
   manifest.append(sample.id()).append('|').append(sample.assetId()).append('|').append(sample.version()).append('|').append(sample.problemAt()).append('|').append(sample.firstAckAt()).append('|').append(sample.handlingState()).append('\n');
  }
  String hash;try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}
  var stats=invalid>0?null:OperationalLatencyStatistics.calculate(durations);
  return new Mtta(invalid>0?"INVALID_SOURCE_TIMESTAMPS":durations.isEmpty()?"NO_ACKNOWLEDGED_SAMPLES":"CALCULATED",population,(long)durations.size(),unack,invalid,stats,hash);
 }
}
