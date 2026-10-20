package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.sla.*;
import cn.mop.domain.time.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Fixed-key server-side configuration consumer. Inputs must come from the authorized DB transaction. */
final class TicketOpeningConfiguration {
    static final List<String> KEYS=List.of("tenant.plan","ticket.solution_min_chars","ticket.customer_confirm_timeout_hours",
            "ticket.reopen_window_hours","ticket.reopen_manager_threshold","sla.p1_response_min","sla.p2_response_min","sla.p3_response_min",
            "sla.p1_recover_min","sla.p2_recover_min","sla.advanced_p3_recover_min","sla.tenant_work_calendar","sla.basic_standard_p3_next_day","sla.reopen_and_mttr_policy");
    record Value(JsonNode value,String reference) {}
    record Plan(String json,Instant responseDue,Instant recoveryDue,SlaClockCalculator.OpeningSnapshot opening) {}
    private final Map<String,Value> values;
    private final ObjectMapper mapper;
    TicketOpeningConfiguration(Map<String,Value> values,ObjectMapper mapper){this.values=Map.copyOf(values);this.mapper=mapper;}
    Plan resolve(Instant openedAt,String priority){
        if(priority==null||!Set.of("P1","P2","P3").contains(priority))throw new DomainException(400,"VALIDATION_ERROR","需明确选择工单优先级P1、P2或P3");
        String label=text(required("tenant.plan"),"tenant.plan");
        SlaClockCalculator.Tier tier=switch(label){case "基础","BASIC"->SlaClockCalculator.Tier.BASIC;case "标准","STANDARD"->SlaClockCalculator.Tier.STANDARD;case "进阶","ADVANCED"->SlaClockCalculator.Tier.ADVANCED;default->throw pending("tenant.plan","UNSUPPORTED_TIER");};
        String chinese=switch(tier){case BASIC->"基础";case STANDARD->"标准";case ADVANCED->"进阶";};
        int min=integer(required("ticket.solution_min_chars"),"ticket.solution_min_chars"),reopen=integer(required("ticket.reopen_window_hours"),"ticket.reopen_window_hours"),threshold=integer(required("ticket.reopen_manager_threshold"),"ticket.reopen_manager_threshold");
        if(min!=20||reopen!=72||threshold!=2)throw pending("ticket.fixed_constraints","FIXED_CONTRACT_MISMATCH");
        int timeout=integer(tierValue("ticket.customer_confirm_timeout_hours",chinese),"ticket.customer_confirm_timeout_hours");
        String responseKey="sla."+priority.toLowerCase(Locale.ROOT)+"_response_min";
        Duration response=minutes(tierValue(responseKey,chinese),responseKey),recovery=null;
        WorkingTime.CalendarData calendar=null;SlaNextDayRule.Rule nextRule=null;Instant nextDeadline=null;
        if(tier==SlaClockCalculator.Tier.BASIC)calendar=calendar();
        if(priority.equals("P3")&&tier!=SlaClockCalculator.Tier.ADVANCED){
            String k="sla.basic_standard_p3_next_day";JsonNode n=required(k);exact(n,Set.of("kind","cutoff","timezone"),k);
            if(!"Asia/Shanghai".equals(text(n.get("timezone"),k)))throw pending(k,"TIMEZONE_MUST_BE_ASIA_SHANGHAI");
            try{String cutoff=text(n.get("cutoff"),k);if(!cutoff.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))throw pending(k,"INVALID_CUTOFF");
                nextRule=new SlaNextDayRule.Rule(SlaNextDayRule.Kind.valueOf(text(n.get("kind"),k)),LocalTime.parse(cutoff),values.get(k).reference());
            }catch(IllegalArgumentException e){throw pending(k,"INVALID_NEXT_DAY_RULE");}
            if(nextRule.kind()==SlaNextDayRule.Kind.WORKING_DAY&&calendar==null)calendar=calendar();
            var result=SlaNextDayRule.resolve(openedAt,nextRule,calendar);if(result.deadline()==null)throw pending(k,result.missing());nextDeadline=result.deadline();
        }else{
            String k=priority.equals("P3")?"sla.advanced_p3_recover_min":"sla."+priority.toLowerCase(Locale.ROOT)+"_recover_min";
            recovery=minutes(priority.equals("P3")?required(k):tierValue(k,chinese),k);
        }
        ObjectNode sources=mapper.createObjectNode();values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{var node=sources.putObject(e.getKey());node.set("value",e.getValue().value());node.put("reference",e.getValue().reference());});
        String reference;
        try{reference="sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(sources)));}catch(Exception e){throw new IllegalStateException("Configuration snapshot digest unavailable",e);}
        var input=new SlaClockCalculator.OpeningSnapshot(tier,SlaClockCalculator.Priority.valueOf(priority),openedAt,reference,response,recovery,calendar,nextDeadline,nextRule==null?null:nextRule.reference());
        var calculated=SlaClockCalculator.initial(input);
        if(calculated.response().at()==null)throw pending(responseKey,calculated.response().reason());
        if(calculated.recovery().at()==null)throw pending("sla.recovery",calculated.recovery().reason());
        ObjectNode snapshot=mapper.createObjectNode();snapshot.put("enabled",true).put("configuration_release_ref",reference).put("service_tier",tier.name()).put("priority",priority)
                .put("solution_min_chars",min).put("confirmation_timeout_hours",timeout).put("reopen_window_hours",reopen).put("manager_threshold",threshold);
        ObjectNode clock=snapshot.putObject("sla_opening");clock.put("opened_at",openedAt.toString()).put("response_budget_ms",response.toMillis());
        if(recovery==null)clock.putNull("recovery_budget_ms");else clock.put("recovery_budget_ms",recovery.toMillis());
        if(nextDeadline==null)clock.putNull("next_day_deadline");else clock.put("next_day_deadline",nextDeadline.toString());
        clock.set("configuration_inputs",sources);
        try{return new Plan(mapper.writeValueAsString(snapshot),calculated.response().at(),calculated.recovery().at(),input);}catch(Exception e){throw new IllegalStateException("Opening snapshot encoding unavailable",e);}
    }
    static Plan restore(String json,ObjectMapper mapper){
        try{
            JsonNode snapshot=mapper.readTree(json),clock=snapshot.path("sla_opening"),sources=clock.path("configuration_inputs");
            if(!clock.isObject()||!sources.isObject())throw pending("sla_opening","IMMUTABLE_OPENING_SNAPSHOT_MISSING");
            var values=new LinkedHashMap<String,Value>();var fields=sources.fields();
            while(fields.hasNext()){var field=fields.next();if(!KEYS.contains(field.getKey()))throw new IllegalArgumentException("Unknown opening configuration key");JsonNode value=field.getValue();exact(value,Set.of("value","reference"),field.getKey());values.put(field.getKey(),new Value(value.get("value"),text(value.get("reference"),field.getKey())));}
            Instant opened=instant(clock.get("opened_at"),"sla_opening.opened_at");
            Plan rebuilt=new TicketOpeningConfiguration(values,mapper).resolve(opened,text(snapshot.get("priority"),"priority"));
            if(!mapper.readTree(rebuilt.json()).equals(snapshot))throw new IllegalArgumentException("Opening snapshot integrity mismatch");return rebuilt;
        }catch(java.io.IOException e){throw new IllegalArgumentException("Opening snapshot cannot be decoded",e);}
    }
    private JsonNode required(String key){Value v=values.get(key);if(v==null||v.value()==null||v.value().isNull()||v.reference()==null||v.reference().isBlank())throw pending(key,"ACTIVE_PUBLICATION_REQUIRED");return v.value();}
    private JsonNode tierValue(String key,String label){JsonNode n=required(key);if(!n.isObject()||!n.has(label))throw pending(key,"TIER_VALUE_MISSING");return n.get(label);}
    private WorkingTime.CalendarData calendar(){
        String k="sla.tenant_work_calendar";JsonNode n=required(k);exact(n,Set.of("coverage_start","coverage_end","working_windows","complete","timezone"),k);
        if(!n.path("complete").isBoolean()||!n.path("complete").booleanValue()||!"Asia/Shanghai".equals(text(n.get("timezone"),k))||!n.path("working_windows").isArray()||n.path("working_windows").size()>1000)throw pending(k,"CALENDAR_INCOMPLETE_OR_INVALID");
        try{var windows=new ArrayList<IntervalSets.Interval>();for(JsonNode w:n.get("working_windows")){exact(w,Set.of("start","end"),k);windows.add(new IntervalSets.Interval(instant(w.get("start"),k),instant(w.get("end"),k)));}
            return new WorkingTime.CalendarData(values.get(k).reference(),1,new IntervalSets.Interval(instant(n.get("coverage_start"),k),instant(n.get("coverage_end"),k)),windows,true);
        }catch(IllegalArgumentException e){throw pending(k,"INVALID_CALENDAR_WINDOWS");}
    }
    private static Instant instant(JsonNode n,String key){try{String s=text(n,key);Instant i=Instant.parse(s);if(!s.endsWith("Z")||i.getNano()%1000000!=0||i.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||i.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw pending(key,"INVALID_UTC_MILLISECONDS");return i;}catch(DateTimeException e){throw pending(key,"INVALID_UTC_MILLISECONDS");}}
    private static String text(JsonNode n,String key){if(n==null||!n.isTextual()||n.textValue().isBlank())throw pending(key,"TEXT_REQUIRED");return n.textValue();}
    private static int integer(JsonNode n,String key){if(n==null||!n.isIntegralNumber()||!n.canConvertToInt()||n.intValue()<1)throw pending(key,"POSITIVE_INTEGER_REQUIRED");return n.intValue();}
    private static Duration minutes(JsonNode n,String key){try{if(n==null||!n.isNumber()||n.decimalValue().signum()<=0)throw pending(key,"POSITIVE_DURATION_REQUIRED");long ms=n.decimalValue().multiply(BigDecimal.valueOf(60000)).longValueExact();if(ms<1)throw pending(key,"MILLISECOND_PRECISION_REQUIRED");return Duration.ofMillis(ms);}catch(ArithmeticException e){throw pending(key,"DURATION_OUT_OF_RANGE");}}
    private static void exact(JsonNode n,Set<String> keys,String key){if(n==null||!n.isObject()||n.size()!=keys.size()||!keys.stream().allMatch(n::has))throw pending(key,"INVALID_CONFIGURATION_FIELDS");}
    private static DomainException pending(String key,String reason){return new DomainException(422,"CONFIG_PENDING","开单所需配置未完整生效",List.of(Map.of("configuration_key",key,"reason",reason)));}
}
