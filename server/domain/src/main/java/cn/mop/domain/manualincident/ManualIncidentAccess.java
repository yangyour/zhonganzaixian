package cn.mop.domain.manualincident;
import cn.mop.domain.*;
/** Technical internal-role gate; not a customer-approved production authority or recovery workflow definition. */
public final class ManualIncidentAccess {
    private ManualIncidentAccess(){}
    public static void require(Membership current,long expectedPermissionVersion,String objectTenant){
        if(current==null||!current.active()||current.userId()==null||current.userId().isBlank()||current.tenantId()==null||current.tenantId().isBlank()||current.permissionVersion()<1||expectedPermissionVersion<1||current.permissionVersion()!=expectedPermissionVersion)throw new DomainException(401,"SESSION_EXPIRED","当前成员或权限版本已失效");
        if(!Capabilities.internal(current.role()))throw new DomainException(403,"ACTION_FORBIDDEN","手工来源登记仅限当前获准内部测试角色");
        if(objectTenant==null||!current.tenantId().equals(objectTenant))throw new DomainException(404,"OBJECT_NOT_FOUND","关联对象不存在或不可见");
    }
}
