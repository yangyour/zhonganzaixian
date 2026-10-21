package cn.mop.api;

import cn.mop.adapters.email.*;
import cn.mop.domain.DomainException;
import cn.mop.persistence.PostgresEmailRepository;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.map;

public final class EmailService {
    public static final String TEST_SUBJECT = "【MOP 联调测试】邮件通道验证";
    public static final String TEST_MESSAGE = "这是一封 MOP 运维服务门户的邮件通道联调测试邮件，无需处理。\n本邮件只用于验证明确发起的测试发送，不代表自动业务通知已经启用。";
    private final SmtpMailClient client; private final PostgresEmailRepository repository;
    private final String tenant; private final boolean dispatchEnabled;
    private volatile Map<String,Object> lastCheck=map("ready",false,"reason","NOT_CHECKED");
    public EmailService(SmtpMailClient client,PostgresEmailRepository repository,String tenant,boolean dispatchEnabled) {this.client=client;this.repository=repository;this.tenant=tenant;this.dispatchEnabled=dispatchEnabled;}
    public String tenant(){return tenant;} public String sender(){return client.settings().sender();}
    public String testRecipient(){return client.settings().testRecipient();}
    public Map<String,Object> status(){return map("enabled",true,"tenant_id",tenant,"sender",sender(),"sender_name",client.settings().senderName(),"smtp_host",client.settings().host(),"smtp_port",465,"tls_mode","IMPLICIT_TLS","certificate_verification",true,"secret_configured",true,"allowed_recipients",client.settings().allowedRecipients(),"test_recipient",testRecipient(),"dispatch_enabled",dispatchEnabled,"automatic_notifications_enabled",false,"receipt_query_supported",false,"last_check",lastCheck,"source","configured_provider");}
    public synchronized Map<String,Object> check(){
        var problems=repository.readinessProblems();
        if(!problems.isEmpty())return lastCheck=map("ready",false,"reason",problems.get(0),"checked_at",Instant.now().toString());
        try{client.check();return lastCheck=map("ready",true,"reason",null,"smtp_authenticated",true,"checked_at",Instant.now().toString());}
        catch(SmtpMailClient.Failure failure){return lastCheck=map("ready",false,"reason",failure.category,"smtp_authenticated",false,"checked_at",Instant.now().toString());}
    }
    public String bindingAddress(String value,boolean active){
        try{String address=SmtpSettings.address(value);if(active&&!client.settings().allows(address))throw new DomainException(422,"EMAIL_RECIPIENT_NOT_ALLOWED","该收件地址尚未列入服务端允许范围");return address;}
        catch(IllegalArgumentException failure){throw new DomainException(400,"VALIDATION_ERROR","邮箱地址格式无效");}
    }
    public void requireDispatch(){if(!dispatchEnabled)throw new DomainException(503,"EMAIL_DISPATCH_DISABLED","邮件发送尚未启用");}
    @Scheduled(fixedDelay=5000,initialDelay=5000) public void dispatch(){
        if(!dispatchEnabled)return;
        try{for(int n=0;n<10;n++){var next=repository.claim(tenant,sender());if(next.isEmpty())break;send(next.get());}}
        catch(RuntimeException failure){System.getLogger(EmailService.class.getName()).log(System.Logger.Level.WARNING,"Email worker dependency unavailable; durable submission state retained");}
    }
    private void send(PostgresEmailRepository.Claim claim){
        if(!claim.authorized()||!sender().equals(claim.sender())||!client.settings().allows(claim.recipient())){repository.finish(claim,"CANCELLED",null,"EMAIL_AUTHORIZATION_OR_BINDING_CHANGED",null);return;}
        try{var result=client.send(claim.id(),claim.recipient(),claim.subject(),claim.text());repository.finish(claim,"ACCEPTED",result.messageId(),null,result.smtpCode());}
        catch(SmtpMailClient.Failure failure){repository.finish(claim,failure.outcomeUnknown?"UNKNOWN":"FAILED",null,failure.category,failure.smtpCode);}
        // A lost database response after acceptance leaves PROCESSING; recovery records UNKNOWN, never resends.
    }
}
