package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.configuration.RuntimeConfigurationRepository;
import cn.mop.domain.ticket.*;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.time.IntervalSets.Interval;
import cn.mop.domain.time.WorkingTime.CalendarData;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Reads fixed keys in the opening transaction; disabled/expired publications never fall back to defaults. */
public final class TicketRuntimePolicy implements TicketWorkflowRepository.SlaProvider {
    private final RuntimeConfigurationRepository repository;
    private final RuntimeConfigurationCatalog catalog;
    private final ObjectMapper mapper;
    public TicketRuntimePolicy(RuntimeConfigurationRepository repository,RuntimeConfigurationCatalog catalog,ObjectMapper mapper){this.repository=repository;this.catalog=catalog;this.mapper=mapper;}
    @Override public TicketSlaSnapshot freeze(TicketWorkflowRepository.Access a,String priority,Tier tier,Instant start){
        if(!Set.of("P1","P2","P3").contains(priority==null?"":priority)||tier==null)throw invalid();
        var rows=repository.ticketInputs(new RuntimeConfigurationRepository.Access(a.tenantId(),a.actorId(),a.permissionVersion(),a.traceId()));
        if(rows==null||rows.size()!=RuntimeConfigurationRepository.TICKET_KEYS.size())throw unavailable();
        Map<String,JsonNode> values=new LinkedHashMap<>();Map<String,String> problems=new LinkedHashMap<>();var refs=new ArrayList<TicketSlaSnapshot.Reference>();String observed=null;
        for(int i=0;i<rows.size();i++){
            var row=rows.get(i);String key=RuntimeConfigurationRepository.TICKET_KEYS.get(i),definition=catalog.hash(key);
            if(row==null||!key.equals(row.get("configuration_key"))||!a.tenantId().equals(row.get("tenant_id"))||!"postgresql".equals(row.get("source")))throw unavailable();
            String at=String.valueOf(row.get("observed_at"));try{Instant.parse(at);}catch(Exception e){throw unavailable();}
            if(observed!=null&&!observed.equals(at))throw unavailable();observed=at;
            String status=String.valueOf(row.get("status"));Map<?,?> release=row.get("selected_release") instanceof Map<?,?> m?m:null;Object value=null;
            if(status.equals("ACTIVE")){
                if(release==null||!definition.equals(release.get("definition_hash"))){problems.put(key,"DEFINITION_CHANGED");continue;}
                value=row.get("effective_value");
                try{catalog.validate(key,mapper.valueToTree(value));refs.add(new TicketSlaSnapshot.Reference(key,release.get("id").toString(),((Number)release.get("version")).longValue(),definition,"RUNTIME_RELEASE"));}
                catch(RuntimeException e){problems.put(key,"INVALID_VALUE");continue;}
            }else if(Set.of("UNCONFIGURED","SCHEDULED").contains(status)&&release==null){
                var entry=catalog.entry(key);if("configurable_default".equals(entry.get("contract_value_kind"))){value=entry.get("value");refs.add(new TicketSlaSnapshot.Reference(key,null,0,definition,"CONTRACT_DEFAULT"));}
            }else if(!Set.of("DISABLED","EXPIRED").contains(status)||release==null)throw unavailable();
            if(value==null)problems.put(key,status);else values.put(key,mapper.valueToTree(value));
        }
        JsonNode authority=values.get("itsm.local_authority_mode");
        if(authority!=null&&!authority.asText().equals("MOP"))throw new DomainException(422,"EXTERNAL_AUTHORITY_NOT_READY","当前配置选择外部工单权威，适配尚未就绪，不能在本平台伪造完成");
        if(Set.of("DISABLED","EXPIRED","DEFINITION_CHANGED","INVALID_VALUE").contains(problems.getOrDefault("itsm.local_authority_mode","")))throw new DomainException(422,"WORKFLOW_DISABLED","工单权威配置已停用、到期或失效");
        String label=switch(tier){case BASIC->"基础";case STANDARD->"标准";case ADVANCED->"进阶";};
        String responseKey="sla."+priority.toLowerCase(Locale.ROOT)+"_response_min";
        String recoveryKey=priority.equals("P3")?"sla.advanced_p3_recover_min":"sla."+priority.toLowerCase(Locale.ROOT)+"_recover_min";
        Map<String,String> missing=new LinkedHashMap<>();
        Duration response=minutes(values.get(responseKey),label,responseKey,problems,missing,"response");
        Duration recovery=priority.equals("P3")&&tier!=Tier.ADVANCED?null:minutes(values.get(recoveryKey),label,recoveryKey,problems,missing,"recovery");
        Long hours=null;try{JsonNode h=member(values.get("ticket.customer_confirm_timeout_hours"),label);if(h!=null&&!h.isNull()){hours=h.decimalValue().longValueExact();if(hours<1||hours>8760)throw new IllegalArgumentException();}}
        catch(RuntimeException e){missing.put("confirmation","INVALID_VALUE");hours=null;}
        CalendarData calendar=null;
        if(tier==Tier.BASIC||priority.equals("P3")&&tier!=Tier.ADVANCED){
            try{calendar=calendar(values.get("sla.tenant_work_calendar"));}catch(RuntimeException e){missing.put("calendar","INVALID_VALUE");}
        }
        Instant next=null;
        if(priority.equals("P3")&&tier!=Tier.ADVANCED){
            try{next=nextDay(values.get("sla.basic_standard_p3_next_day"),start,calendar);if(next==null)missing.put("recovery",problems.getOrDefault("sla.basic_standard_p3_next_day","NEXT_DAY_RULE_UNCONFIGURED"));}
            catch(RuntimeException e){missing.put("recovery","NEXT_DAY_RULE_INVALID_OR_OUTSIDE_COVERAGE");}
        }
        return TicketSlaSnapshot.freeze(priority,tier,start,response,recovery,next,hours,calendar,refs,missing);
    }
    private static JsonNode member(JsonNode node,String tier){return node==null?null:node.isObject()?node.get(tier):node;}
    private static Duration minutes(JsonNode node,String tier,String key,Map<String,String> problems,Map<String,String> missing,String clock){
        JsonNode value=member(node,tier);if(value==null){missing.put(clock,problems.getOrDefault(key,"TIER_VALUE_MISSING"));return null;}
        try{if(!value.isNumber())throw new IllegalArgumentException();long ms=value.decimalValue().multiply(BigDecimal.valueOf(60000)).longValueExact();if(ms<=0||ms>Duration.ofDays(366).toMillis())throw new IllegalArgumentException();return Duration.ofMillis(ms);}
        catch(RuntimeException e){missing.put(clock,"INVALID_VALUE");return null;}
    }
    static CalendarData calendar(JsonNode value){
        if(value==null)return null;fields(value,Set.of("calendar_id","version","coverage_start","coverage_end","working_windows","complete"));
        if(!value.path("calendar_id").isTextual()||!value.path("version").isIntegralNumber()||!value.path("version").canConvertToLong()||!value.path("complete").isBoolean()||!value.path("working_windows").isArray()||value.path("working_windows").size()>1000)throw invalid();
        List<Interval> windows=new ArrayList<>();for(JsonNode w:value.path("working_windows")){fields(w,Set.of("start","end"));windows.add(new Interval(time(w.get("start")),time(w.get("end"))));}
        return new CalendarData(value.path("calendar_id").asText(),value.path("version").longValue(),new Interval(time(value.get("coverage_start")),time(value.get("coverage_end"))),windows,value.path("complete").booleanValue());
    }
    static Instant nextDay(JsonNode value,Instant start,CalendarData calendar){
        if(value==null)return null;fields(value,Set.of("timezone","day_offset","deadline_local_time","day_kind"));
        if(!value.path("timezone").isTextual()||!value.path("deadline_local_time").isTextual()||!value.path("day_kind").isTextual()
                ||!value.path("day_offset").isIntegralNumber()||!value.path("day_offset").canConvertToInt())throw invalid();
        ZoneId zone=ZoneId.of(value.path("timezone").textValue());int days=value.path("day_offset").intValue();if(days<1||days>366)throw invalid();
        String kind=value.path("day_kind").textValue();if(!Set.of("CALENDAR","WORKING").contains(kind))throw invalid();
        String raw=value.path("deadline_local_time").textValue();if(!raw.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))throw invalid();LocalTime time=LocalTime.parse(raw);LocalDate day=start.atZone(zone).toLocalDate();
        if(kind.equals("CALENDAR"))day=day.plusDays(days);
        else {
            if(calendar==null||!calendar.complete())throw invalid();int counted=0;
            for(int n=0;n<366&&counted<days;n++){
                day=day.plusDays(1);Instant from=day.atStartOfDay(zone).toInstant(),to=day.plusDays(1).atStartOfDay(zone).toInstant();
                if(from.isBefore(calendar.coverage().start())||to.isAfter(calendar.coverage().end()))throw invalid();
                if(calendar.workingWindows().stream().anyMatch(w->w.start().isBefore(to)&&w.end().isAfter(from)))counted++;
            }if(counted!=days)throw invalid();
        }
        LocalDateTime local=LocalDateTime.of(day,time);var offsets=zone.getRules().getValidOffsets(local);if(offsets.size()!=1)throw invalid();
        Instant result=local.toInstant(offsets.get(0));TicketSlaSnapshot.time(result);return result;
    }
    private static Instant time(JsonNode value){if(value==null||!value.isTextual())throw invalid();Instant t=Instant.parse(value.textValue());TicketSlaSnapshot.time(t);return t;}
    private static void fields(JsonNode value,Set<String> allowed){if(value==null||!value.isObject()||value.size()!=allowed.size())throw invalid();value.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw invalid();});}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","运行SLA配置缺少明确字段或不在支持范围内");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","工单运行配置来源未通过核对");}
}
