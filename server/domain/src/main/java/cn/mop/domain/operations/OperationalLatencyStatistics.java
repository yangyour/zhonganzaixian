package cn.mop.domain.operations;
import java.math.*;
import java.util.*;
/** Shared duration arithmetic for project metrics and explicit comparisons. Source authorization is separate. */
public final class OperationalLatencyStatistics {
 public static final int MAX_SAMPLES=10000;
 public static final String VERSION="mop-elapsed-statistics-v1";
 private OperationalLatencyStatistics(){}
 public record Result(int sampleCount,BigDecimal meanMillis,BigDecimal medianMillis,BigInteger p95Millis){}
 public static Result calculate(List<BigInteger> values){
  if(values==null||values.isEmpty())return new Result(0,null,null,null);
  if(values.size()>MAX_SAMPLES)throw new IllegalArgumentException("Duration sample budget exceeded");
  var sorted=new ArrayList<BigInteger>();BigInteger sum=BigInteger.ZERO;
  for(var value:values){if(value==null||value.signum()<0||value.toString().length()>40)throw new IllegalArgumentException("Explicit nonnegative durations required");sorted.add(value);sum=sum.add(value);}
  sorted.sort(BigInteger::compareTo);int n=sorted.size();BigDecimal median=n%2==1?new BigDecimal(sorted.get(n/2)):new BigDecimal(sorted.get(n/2-1).add(sorted.get(n/2))).divide(BigDecimal.valueOf(2));
  return new Result(n,new BigDecimal(sum).divide(BigDecimal.valueOf(n),MathContext.DECIMAL128),median,sorted.get((95*n+99)/100-1));
 }
 /** Compatibility adapter for the existing seconds DTO, preserving its nonnegative half-up presentation rounding. */
 public static Long seconds(BigDecimal milliseconds){return milliseconds==null?null:milliseconds.divide(BigDecimal.valueOf(1000),0,RoundingMode.HALF_UP).longValueExact();}
}
