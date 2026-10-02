package cn.mop.domain.ticketcomment;

import cn.mop.domain.*;

/** Pure checks only. Membership, reporter and tenant must come from the same fresh authorized DB transaction. */
public final class TicketCommentAccess {
    private TicketCommentAccess() {}
    public enum AuthorKind {
        REPORTER("报障人"), ENGINEER("工程师");
        private final String publicLabel;
        AuthorKind(String publicLabel){this.publicLabel=publicLabel;}
        public String publicLabel(){return publicLabel;}
    }
    public static void require(Membership member,long expectedPermissionVersion,String parentTenant,String reporterId){
        if(member==null||!member.active()||member.userId()==null||member.userId().isBlank()||expectedPermissionVersion<1||member.permissionVersion()!=expectedPermissionVersion)throw new DomainException(401,"SESSION_EXPIRED","当前成员或权限版本已失效");
        if(parentTenant==null||parentTenant.isBlank()||!parentTenant.equals(member.tenantId()))throw missing();
        if(!Capabilities.customer(member.role())&&!Capabilities.internal(member.role()))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无客户评论权限");
        if(reporterId==null||reporterId.isBlank())throw new DomainException(503,"TICKET_REPORTER_UNAVAILABLE","报障人关系尚未完整核验，不能公开评论");
        if(Capabilities.customer(member.role())&&!member.userId().equals(reporterId))throw missing();
    }
    /** Freeze at append time; later role changes must not rewrite historical public attribution. */
    public static AuthorKind authorKind(String verifiedRole){
        if(Capabilities.customer(verifiedRole))return AuthorKind.REPORTER;
        if(Capabilities.internal(verifiedRole))return AuthorKind.ENGINEER;
        throw new DomainException(403,"ACTION_FORBIDDEN","作者角色未经核验");
    }
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
}
