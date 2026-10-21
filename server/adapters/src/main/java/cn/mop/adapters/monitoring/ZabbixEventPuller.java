package cn.mop.adapters.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

/** Bounded Zabbix 7.0 trigger-event read pages. No watermark commit, source authorization or event processing. */
public final class ZabbixEventPuller {
    private static final BigInteger MAX_ID=new BigInteger("18446744073709551615");
    private static final long MAX_CLOCK=253402300799L;
    private static final Set<String> FIELDS=Set.of("eventid","source","object","objectid","clock","ns","value","severity","r_eventid","hosts");
    public static final class Failure extends IllegalStateException {
        public final String reason;
        Failure(String reason){super("Zabbix event read unavailable: "+reason);this.reason=reason;}
    }
    public record Query(UUID poolId,String firstEventId,String lastEventId,long fromClock,long throughClock,int pageSize){
        public Query{
            if(poolId==null||!id(firstEventId,false)||!id(lastEventId,false)||new BigInteger(firstEventId).compareTo(new BigInteger(lastEventId))>0||
                    fromClock<0||throughClock<fromClock||throughClock>MAX_CLOCK||pageSize<1||pageSize>1000)throw invalid();
        }
    }
    public record SourceEvent(String eventId,String triggerId,long clock,int nanoseconds,int value,int severity,String recoveryEventId,List<String> hostIds){
        public SourceEvent{
            if(!id(eventId,false)||!id(triggerId,false)||!id(recoveryEventId,true)||clock<0||clock>MAX_CLOCK||nanoseconds<0||nanoseconds>999999999||value<0||value>1||severity<0||severity>5||hostIds==null||hostIds.size()>16||hostIds.stream().anyMatch(h->!id(h,false))||new HashSet<>(hostIds).size()!=hostIds.size())throw invalid();
            hostIds=List.copyOf(hostIds);
        }
        public Instant occurredAt(){return Instant.ofEpochSecond(clock,nanoseconds);}
        @Override public String toString(){return "ZabbixSourceEvent[unverified source fields redacted]";}
    }
    public static final class Page {
        private final Query query;private final List<SourceEvent> events;private final String nextEventId;
        private Page(Query query,List<SourceEvent> events,String next){this.query=query;this.events=List.copyOf(events);this.nextEventId=next;}
        public Query query(){return query;}public List<SourceEvent> events(){return events;}public String nextEventId(){return nextEventId;}
        public boolean hasMore(){return nextEventId!=null;}public boolean completeWindowClaimed(){return false;}
        public boolean durablyStored(){return false;}public boolean mappingVerified(){return false;}
        @Override public String toString(){return "ZabbixEventPage[count="+events.size()+", hasMore="+hasMore()+", unverified]";}
    }
    @FunctionalInterface interface Reader{JsonNode read(Map<String,Object> params);}
    private final UUID poolId;private final Reader reader;
    public ZabbixEventPuller(UUID poolId,ZabbixReadClient client){this(poolId,params->Objects.requireNonNull(client).call("event.get",params));Objects.requireNonNull(client);}
    ZabbixEventPuller(UUID poolId,Reader reader){this.poolId=Objects.requireNonNull(poolId);this.reader=Objects.requireNonNull(reader);}
    public Page read(Query query){
        Objects.requireNonNull(query);if(!poolId.equals(query.poolId()))throw invalid();
        try{return decode(query,reader.read(parameters(query)));}catch(Failure f){throw f;}catch(RuntimeException f){throw new Failure("UPSTREAM_READ_FAILED");}
    }
    static Map<String,Object> parameters(Query q){
        return Map.ofEntries(Map.entry("source",0),Map.entry("object",0),
                Map.entry("output",List.of("eventid","source","object","objectid","clock","ns","value","severity","r_eventid")),
                Map.entry("selectHosts",List.of("hostid")),Map.entry("eventid_from",q.firstEventId()),Map.entry("eventid_till",q.lastEventId()),
                Map.entry("time_from",q.fromClock()),Map.entry("time_till",q.throughClock()),Map.entry("sortfield",List.of("eventid")),
                Map.entry("sortorder","ASC"),Map.entry("limit",q.pageSize()+1));
    }
    static Page decode(Query query,JsonNode rows){
        if(rows==null||!rows.isArray()||rows.size()>query.pageSize()+1)throw new Failure("PAGE_SHAPE_REJECTED");
        var events=new ArrayList<SourceEvent>();BigInteger previous=null;
        for(JsonNode row:rows){
            if(!fields(row,FIELDS)||!"0".equals(text(row,"source"))||!"0".equals(text(row,"object")))throw new Failure("EVENT_SOURCE_REJECTED");
            String event=text(row,"eventid"),trigger=text(row,"objectid"),recovery=text(row,"r_eventid");
            if(!id(event,false)||!id(trigger,false)||!id(recovery,true))throw new Failure("EVENT_ID_REJECTED");
            BigInteger numeric=new BigInteger(event);
            if(numeric.compareTo(new BigInteger(query.firstEventId()))<0||numeric.compareTo(new BigInteger(query.lastEventId()))>0||previous!=null&&numeric.compareTo(previous)<=0)throw new Failure("EVENT_ORDER_REJECTED");
            previous=numeric;long clock=whole(row,"clock",MAX_CLOCK);int ns=(int)whole(row,"ns",999999999);
            if(clock<query.fromClock()||clock>query.throughClock())throw new Failure("EVENT_TIME_REJECTED");
            int value=(int)whole(row,"value",1),severity=(int)whole(row,"severity",5);
            JsonNode hosts=row.get("hosts");if(!hosts.isArray()||hosts.size()>16)throw new Failure("HOSTS_REJECTED");
            var hostIds=new ArrayList<String>();var unique=new HashSet<String>();
            for(var host:hosts){String hostId=text(host,"hostid");if(!fields(host,Set.of("hostid"))||!id(hostId,false)||!unique.add(hostId))throw new Failure("HOSTS_REJECTED");hostIds.add(hostId);}
            events.add(new SourceEvent(event,trigger,clock,ns,value,severity,recovery,hostIds));
        }
        String next=null;
        if(events.size()>query.pageSize()){
            events.remove(events.size()-1);
            next=new BigInteger(events.get(events.size()-1).eventId()).add(BigInteger.ONE).toString();
        }
        return new Page(query,events,next);
    }
    private static long whole(JsonNode row,String key,long max){String s=text(row,key);if(s==null||s.length()>12||!s.matches("(?:0|[1-9][0-9]*)"))throw new Failure("EVENT_NUMBER_REJECTED");try{long n=Long.parseLong(s);if(n>max)throw new Failure("EVENT_NUMBER_REJECTED");return n;}catch(NumberFormatException f){throw new Failure("EVENT_NUMBER_REJECTED");}}
    private static String text(JsonNode node,String field){if(node==null)return null;var value=node.get(field);return value!=null&&value.isTextual()?value.textValue():null;}
    private static boolean fields(JsonNode node,Set<String> expected){if(node==null||!node.isObject()||node.size()!=expected.size())return false;var names=node.fieldNames();while(names.hasNext())if(!expected.contains(names.next()))return false;return true;}
    private static boolean id(String s,boolean zero){return s!=null&&s.matches(zero?"(?:0|[1-9][0-9]{0,19})":"[1-9][0-9]{0,19}")&&new BigInteger(s).compareTo(MAX_ID)<=0;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Explicit bounded pool-scoped event query required");}
}
