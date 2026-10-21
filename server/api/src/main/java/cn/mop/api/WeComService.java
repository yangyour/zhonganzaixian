package cn.mop.api;

import cn.mop.adapters.wecom.*;
import cn.mop.domain.DomainException;
import cn.mop.persistence.PostgresWeComRepository;
import org.springframework.scheduling.annotation.Scheduled;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static cn.mop.api.ApiResponse.map;

public final class WeComService {
    public static final String TEST_MESSAGE="【MOP 联调测试】企业微信应用消息通道验证，无需处理";
    private final WeComClient client; private final PostgresWeComRepository repository;
    private final String tenant,testUser,callback;private final boolean loginEnabled,dispatchEnabled;
    private final SecureRandom random=new SecureRandom();
    private volatile Map<String,Object> lastCheck=map("ready",false,"reason","NOT_CHECKED");
    public WeComService(WeComClient client,PostgresWeComRepository repository,String tenant,String testUser,boolean loginEnabled,String callback,boolean dispatchEnabled) {
        this.client=client;this.repository=repository;this.tenant=tenant;this.testUser=testUser;this.loginEnabled=loginEnabled;this.callback=callback;this.dispatchEnabled=dispatchEnabled;
    }
    public String tenant(){return tenant;} public String corp(){return client.corpId();} public int agent(){return client.agentId();}
    public String testUser(){return testUser;} public String callback(){return callback;} public boolean loginEnabled(){return loginEnabled;}
    public Map<String,Object> status(){return map("enabled",true,"tenant_id",tenant,"corp_id",corp(),"agent_id",agent(),"secret_configured",true,"test_user_id",testUser.isBlank()?null:testUser,"login_enabled",loginEnabled,"callback_url",loginEnabled?callback:null,"dispatch_enabled",dispatchEnabled,"automatic_notifications_enabled",false,"last_check",lastCheck,"source","configured_provider");}
    public synchronized Map<String,Object> check() {
        var problems=repository.readinessProblems();
        if(!problems.isEmpty())return lastCheck=map("ready",false,"reason",problems.get(0),"provider_code",null,"checked_at",java.time.Instant.now().toString());
        try{var app=client.application();lastCheck=map("ready",!app.closed(),"reason",app.closed()?"WECOM_APPLICATION_DISABLED":null,"application_name",app.name(),"provider_code",0,"checked_at",java.time.Instant.now().toString());}
        catch(WeComClient.Failure e){lastCheck=map("ready",false,"reason",e.category,"provider_code",e.providerCode,"checked_at",java.time.Instant.now().toString());}
        return lastCheck;
    }
    public WeComClient.Member verifyMember(String member) {
        try{var verified=client.member(member);if(!Objects.equals(verified.status(),1))throw new DomainException(422,"WECOM_MEMBER_INACTIVE","企业微信成员尚未激活或已停用");return verified;}
        catch(WeComClient.Failure e){throw provider(e);}catch(IllegalArgumentException e){throw new DomainException(400,"VALIDATION_ERROR","企业微信成员标识无效");}
    }
    public String identity(String code){try{return client.identity(code);}catch(WeComClient.Failure e){throw provider(e);}}
    public record LoginStart(String state,String browserCookie,String authorizationUrl) {}
    public LoginStart startLogin(String mode) {
        if(!loginEnabled)throw new DomainException(404,"NOT_FOUND","企业微信登录未启用");
        if(!Set.of("web","work").contains(mode))throw new DomainException(400,"VALIDATION_ERROR","登录方式无效");
        String state=nonce(),browser=nonce();repository.createLoginState(hash(state),hash(browser),tenant,corp(),agent());
        String url="web".equals(mode)?"https://login.work.weixin.qq.com/wwlogin/sso/login?login_type=CorpApp&appid="+encode(corp())+"&agentid="+agent()+"&redirect_uri="+encode(callback)+"&state="+state
            :"https://open.weixin.qq.com/connect/oauth2/authorize?appid="+encode(corp())+"&redirect_uri="+encode(callback)+"&response_type=code&scope=snsapi_base&state="+state+"&agentid="+agent()+"#wechat_redirect";
        return new LoginStart(state,browser,url);
    }
    public boolean consumeLogin(String state,String browser) {
        if(!loginEnabled||state==null||browser==null||!state.matches("[A-Za-z0-9_-]{43}")||!browser.matches("[A-Za-z0-9_-]{43}"))return false;
        return repository.consumeLoginState(hash(state),hash(browser),tenant,corp(),agent());
    }
    @Scheduled(fixedDelay=5000,initialDelay=5000) public void dispatch() {
        if(!dispatchEnabled)return;
        try{for(int n=0;n<10;n++){var next=repository.claim(tenant,corp(),agent());if(next.isEmpty())break;send(next.get());}}
        catch(RuntimeException e){System.getLogger(WeComService.class.getName()).log(System.Logger.Level.WARNING,"WeCom worker dependency unavailable; durable delivery state retained");}
    }
    private void send(PostgresWeComRepository.Claim claim) {
        if(!claim.authorized()){repository.finish(claim,"CANCELLED",null,"AUTHORIZATION_OR_BINDING_CHANGED",null);return;}
        try{var app=client.application();if(app.closed()){repository.finish(claim,"FAILED",null,"WECOM_APPLICATION_DISABLED",null);return;}}
        catch(WeComClient.Failure failure){repository.finish(claim,"FAILED",null,failure.category,failure.providerCode);return;}
        try{var result=client.sendText(claim.recipient(),claim.text());repository.finish(claim,"ACCEPTED",result.messageId(),null,0);}
        catch(WeComClient.Failure failure){repository.finish(claim,failure.outcomeUnknown?"UNKNOWN":"FAILED",null,failure.category,failure.providerCode);}
        // A persistence failure after provider acceptance leaves PROCESSING; recovery records UNKNOWN, never resends.
    }
    public static void validateCallback(String value,boolean allowHttp) {
        try{var uri=URI.create(value);if(!("https".equals(uri.getScheme())||allowHttp&&"http".equals(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!"/api/v1/auth/wecom/callback".equals(uri.getPath()))throw new IllegalArgumentException();}
        catch(RuntimeException e){throw new IllegalArgumentException("A fixed WeCom callback URL is required; HTTP requires explicit test configuration");}
    }
    private String nonce(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException();}}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    static DomainException provider(WeComClient.Failure e){return new DomainException(503,e.category,"企业微信接口暂不可用，请核对应用权限、可信IP或凭据",List.of(map("provider_code",e.providerCode,"outcome_unknown",e.outcomeUnknown)));}
}
