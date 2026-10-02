package cn.mop.api;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
public final class ApiResponse {
    private ApiResponse(){}
    public static Map<String,Object> success(Object data,HttpServletRequest request){return Map.of("data",data,"trace_id",trace(request));}
    public static Map<String,Object> failure(String code,String message,List<?> details,HttpServletRequest request){return Map.of("error",Map.of("code",code,"message",message,"details",details),"trace_id",trace(request));}
    public static String trace(HttpServletRequest request){Object id=request.getAttribute("mop.trace_id");return id==null?"unavailable":id.toString();}
    public static String route(HttpServletRequest request){return request.getRequestURI().substring(request.getContextPath().length());}
    /** Malformed session scope must not mask a denial with a secondary ClassCastException. */
    public static String auditTenant(HttpServletRequest request){Object value=request.getSession(false)==null?null:request.getSession(false).getAttribute("mop.tenant_id");return value instanceof String tenant&&!tenant.isBlank()?tenant:null;}
    public static Map<String,Object> map(Object... pairs){Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)map.put(pairs[i].toString(),pairs[i+1]);return map;}
}
