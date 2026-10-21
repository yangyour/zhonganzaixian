package cn.mop.api;
import cn.mop.domain.DomainException;
import cn.mop.domain.PlatformRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.List;
import java.util.Map;

@RestControllerAdvice @Profile({"local_fixture","remote_test"})
public class ApiExceptionHandler {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final PlatformRepository repository;
    public ApiExceptionHandler(PlatformRepository repository){this.repository=repository;}
    @ExceptionHandler(DomainException.class)
    ResponseEntity<Map<String,Object>> domain(DomainException exception,HttpServletRequest request){
        audit(request,exception.code);
        return ResponseEntity.status(exception.status).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(ApiResponse.failure(exception.code,exception.getMessage(),exception.details,request));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String,Object>> invalid(Exception exception,HttpServletRequest request){audit(request,"VALIDATION_ERROR");return ResponseEntity.badRequest().contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(ApiResponse.failure("VALIDATION_ERROR","请求必须为合法 JSON 和约定字段类型",List.of(),request));}
    @ExceptionHandler({org.springframework.web.multipart.MultipartException.class,org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<Map<String,Object>> multipart(Exception exception,HttpServletRequest request){audit(request,"VALIDATION_ERROR");String message=ApiResponse.route(request).equals("/api/v1/assets/import-sources")?"multipart 格式无效、字段缺失或导入原文件超过20971520 bytes":"multipart 格式无效、字段缺失或照片超过5000000 bytes";return ResponseEntity.badRequest().contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(ApiResponse.failure("VALIDATION_ERROR",message,List.of(),request));}
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String,Object>> fallback(Exception exception,HttpServletRequest request){
        int status=exception instanceof org.springframework.web.ErrorResponse error?error.getStatusCode().value():500;
        String code=switch(status){case 404->"OBJECT_NOT_FOUND";case 405->"METHOD_NOT_ALLOWED";case 415->"UNSUPPORTED_MEDIA_TYPE";case 400->"VALIDATION_ERROR";default->"INTERNAL_ERROR";};
        String message=status>=500?"内部错误，请按trace_id定位":"请求路径、方法或格式不符合接口约定";
        audit(request,code);
        if(status>=500)log.error("API internal failure trace_id={} exception_type={}",ApiResponse.trace(request),exception.getClass().getName());
        return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(ApiResponse.failure(code,message,List.of(),request));
    }
    private void audit(HttpServletRequest request,String code){
        String actor=request.getUserPrincipal()==null?null:request.getUserPrincipal().getName();
        String tenant=ApiResponse.auditTenant(request);
        try{repository.audit(actor,tenant,request.getMethod()+" "+ApiResponse.route(request),"request",null,"DENIED",code,ApiResponse.trace(request));}
        catch(DomainException unavailable){if(unavailable.status!=503)throw unavailable;log.warn("Audit dependency unavailable trace_id={}",ApiResponse.trace(request));}
    }
}
