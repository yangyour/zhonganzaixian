package cn.mop.api;

import cn.mop.domain.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Final standard-JSON read boundary. Not a streaming/download guard or a substitute for tenant authorization. */
@RestControllerAdvice @Profile("remote_test")
@ConditionalOnProperty(name="MOP_AUTH_MODE",havingValue="project_local",matchIfMissing=true)
public final class ProjectReadResponseAdvice implements ResponseBodyAdvice<Object> {
 @Override public boolean supports(MethodParameter returnType,Class<? extends HttpMessageConverter<?>> converterType){return AbstractJackson2HttpMessageConverter.class.isAssignableFrom(converterType);}
 @Override public Object beforeBodyWrite(Object body,MethodParameter returnType,MediaType contentType,Class<? extends HttpMessageConverter<?>> converterType,ServerHttpRequest request,ServerHttpResponse response){
  if(!(request instanceof ServletServerHttpRequest servletRequest)||!(response instanceof ServletServerHttpResponse servletResponse)
    ||request.getMethod()!=HttpMethod.GET&&request.getMethod()!=HttpMethod.HEAD||!servletRequest.getServletRequest().getRequestURI().startsWith("/api/v1/")
    ||servletResponse.getServletResponse().getStatus()<200||servletResponse.getServletResponse().getStatus()>=300
    ||!(MediaType.APPLICATION_JSON.isCompatibleWith(contentType)||contentType.getSubtype().endsWith("+json")))return body;
  try{ProjectSessionValidationFilter.recheckBeforeReturn(servletRequest.getServletRequest());}
  catch(DomainException denied){
   // Do not leave success/download validators on the error body. Security headers/cookies remain untouched.
   for(String name:new String[]{HttpHeaders.CONTENT_LENGTH,HttpHeaders.CONTENT_DISPOSITION,HttpHeaders.ETAG,HttpHeaders.LAST_MODIFIED}){response.getHeaders().remove(name);servletResponse.getServletResponse().setHeader(name,null);}
   response.getHeaders().setCacheControl("no-store");throw denied;
  }
  return body;
 }
}
