package cn.mop.persistence;

import cn.mop.domain.DomainException;
import java.sql.*;
import java.util.*;

/** Read effective grants from the caller's existing transaction snapshot; no in-memory fallback. */
public final class PostgresAuthorization {
    private PostgresAuthorization() {}
    public static List<String> capabilities(Connection c,String actor,String tenant)throws SQLException {
        try(var s=c.prepareStatement("SELECT public.mop_effective_capabilities(?::uuid,?::uuid)")){s.setQueryTimeout(5);s.setString(1,actor);s.setString(2,tenant);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing authorization result");return List.of((String[])r.getArray(1).getArray());}}
    }
    public static void require(Connection c,String actor,String tenant,long version,String capability)throws SQLException {
        try { if(actor==null||tenant==null||version<1||!UUID.fromString(actor).toString().equals(actor)||!UUID.fromString(tenant).toString().equals(tenant))throw new IllegalArgumentException(); }
        catch(IllegalArgumentException invalid){throw new DomainException(401,"SESSION_EXPIRED","当前授权上下文无效");}
        try(var s=c.prepareStatement("SELECT permission_version FROM public.mop_membership WHERE user_id=?::uuid AND tenant_id=?::uuid AND active")){s.setQueryTimeout(5);s.setString(1,actor);s.setString(2,tenant);try(var r=s.executeQuery()){if(!r.next()||r.getLong(1)!=version)throw new DomainException(401,"SESSION_EXPIRED","当前权限版本已失效");}}
        if(!capabilities(c,actor,tenant).contains(capability))throw new DomainException(403,"ACTION_FORBIDDEN","当前有效权限不允许此操作");
    }
}
