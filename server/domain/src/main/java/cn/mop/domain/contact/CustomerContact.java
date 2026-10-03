package cn.mop.domain.contact;

import cn.mop.domain.DomainException;
import java.time.*;
import java.util.*;

/** Explicit customer-contact details, not an identity binding, verified channel or notification routing decision. */
public record CustomerContact(String name,Set<Role> roles,String mobile,String email,String wecomUserId,
                              Set<Priority> preferredPriorities,QuietWindow quietWindow,boolean enabled) {
    public enum Role { REPORTER, DECISION_MAKER, TECHNICAL_CONTACT }
    public enum Priority { P1, P2, P3, P4 }
    public static final String DISPLAY_ZONE="Asia/Shanghai";
    public CustomerContact {
        text(name,200,false);if(roles==null||roles.isEmpty()||roles.stream().anyMatch(Objects::isNull))throw invalid();roles=Set.copyOf(roles);
        if(mobile!=null&&!mobile.matches("\\+[1-9][0-9]{1,14}"))throw invalid();
        if(email!=null)email(email);
        text(wecomUserId,256,true);
        if(preferredPriorities!=null){if(preferredPriorities.stream().anyMatch(Objects::isNull))throw invalid();preferredPriorities=Set.copyOf(preferredPriorities);}
        // Missing preferences/window stay unknown, including for an enabled decision maker.
    }
    public boolean enabledDecisionMaker(){return enabled&&roles.contains(Role.DECISION_MAKER);}
    public String contactVerification(){return "SELF_REPORTED_UNVERIFIED";}
    public String channelVerification(){return "NOT_VERIFIED";}
    public String notificationRoutingStatus(){return "CONFIG_PENDING";}
    public Boolean deliveryEligible(){return enabled?null:Boolean.FALSE;}
    public boolean identityBindingCreated(){return false;}
    public boolean notificationDispatched(){return false;}

    /** Explicit wall-clock preference only. No P1/disabled/decision-maker precedence or dispatch is inferred. */
    public record QuietWindow(Boolean enabled,LocalTime start,LocalTime end) {
        public QuietWindow {
            if(enabled==null){if(start!=null||end!=null)throw invalid();}
            else if(!enabled){if(start!=null||end!=null)throw invalid();}
            else if(start==null||end==null||start.equals(end)||start.getSecond()!=0||end.getSecond()!=0||start.getNano()!=0||end.getNano()!=0)throw invalid();
        }
        public String zone(){return DISPLAY_ZONE;}
        public boolean crossesMidnight(){return Boolean.TRUE.equals(enabled)&&start.isAfter(end);}
        public String verificationStatus(){return "UNVERIFIED_PREFERENCE";}
        public static LocalTime parseMinute(String value){if(value==null||!value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))throw invalid();return LocalTime.parse(value);}
    }
    /**
     * Caller must count enabled decision makers in the current tenant under a shared serialization lock.
     * This pure guard does not establish tenant scope, snapshot consistency, authorization or concurrency safety.
     * Null before/after means create/remove; first contact must establish an enabled decision maker.
     */
    public static long requireDecisionMakerAfterChange(long enabledDecisionMakers,CustomerContact before,CustomerContact after){
        if(enabledDecisionMakers<0||before==null&&after==null||before!=null&&before.enabledDecisionMaker()&&enabledDecisionMakers==0)throw invalid();
        long remaining;
        try{remaining=Math.addExact(enabledDecisionMakers-(before!=null&&before.enabledDecisionMaker()?1:0),after!=null&&after.enabledDecisionMaker()?1:0);}catch(ArithmeticException failure){throw invalid();}
        if(remaining<1)throw new DomainException(409,"LAST_ENABLED_DECISION_MAKER","每个租户必须保留至少一名启用的决策人；请先新增或启用另一名决策人");
        return remaining;
    }
    private static void text(String value,int max,boolean nullable){if(value==null){if(nullable)return;throw invalid();}if(value.isEmpty()||value.codePointCount(0,value.length())>max||value.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)||value.codePoints().anyMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.SURROGATE))throw invalid();}
    /** Bounded ASCII mailbox syntax only, not address ownership or deliverability validation. */
    private static void email(String value){
        if(value.length()>254)throw invalid();int at=value.indexOf('@');if(at<1||at>64||at!=value.lastIndexOf('@')||at==value.length()-1)throw invalid();
        String local=value.substring(0,at),domain=value.substring(at+1);if(local.startsWith(".")||local.endsWith(".")||local.contains("..")||!local.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+"))throw invalid();
        String[] labels=domain.split("\\.",-1);if(labels.length<2)throw invalid();for(String label:labels)if(!label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))throw invalid();
    }
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","联系人字段、角色、规范国际手机号、邮箱或显式分钟静音窗口无效；未知偏好保持null");}
}
