package cn.mop.api;
import cn.mop.domain.DomainException;
import cn.mop.domain.assetexport.AssetExportDownloadGrant;
import cn.mop.domain.assetexport.AssetExportDownloadGrant.Binding;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AssetExportDownloadGrantTest {
    final byte[] key=new byte[32];
    final Instant now=Instant.parse("2026-10-14T00:00:00.123Z");
    final Binding binding=new Binding("55000000-0000-4000-8000-000000000010","55000000-0000-4000-8000-000000000001","55000000-0000-4000-8000-000000000002",4,7,"a".repeat(64));
    @Test void exactFifteenMinuteBoundary(){var signer=new AssetExportDownloadGrant(key);var grant=signer.issue(binding,now);assertEquals(now.plusSeconds(900),grant.expiresAt());signer.verify(binding,grant.token(),now);signer.verify(binding,grant.token(),now.plusMillis(899999));assertEquals(404,assertThrows(DomainException.class,()->signer.verify(binding,grant.token(),now.plusSeconds(900))).status);}
    @Test void differentTenantActorJobVersionOrFileCannotReuseLink(){var signer=new AssetExportDownloadGrant(key);String token=signer.issue(binding,now).token();String other=UUID.randomUUID().toString();for(var b:List.of(new Binding(other,binding.tenantId(),binding.actorId(),4,7,binding.sha256()),new Binding(binding.jobId(),other,binding.actorId(),4,7,binding.sha256()),new Binding(binding.jobId(),binding.tenantId(),other,4,7,binding.sha256()),new Binding(binding.jobId(),binding.tenantId(),binding.actorId(),5,7,binding.sha256()),new Binding(binding.jobId(),binding.tenantId(),binding.actorId(),4,8,binding.sha256()),new Binding(binding.jobId(),binding.tenantId(),binding.actorId(),4,7,"b".repeat(64))))assertEquals(404,assertThrows(DomainException.class,()->signer.verify(b,token,now)).status);}
    @Test void signatureTamperExpiryExtensionAndMalformedInputsAreRejected(){var s=new AssetExportDownloadGrant(key);String t=s.issue(binding,now).token();for(String wrong:Arrays.asList(null,"",t+"=",t+".x","0"+t,t.replaceFirst("[.]","0."),"-1."+t.split("\\.")[1],"999999999999999999."+t.split("\\.")[1]))assertEquals(404,assertThrows(DomainException.class,()->s.verify(binding,wrong,now)).status);}
    @Test void keyIsCopiedAndAnotherKeyCannotValidate(){byte[] original=new byte[32];original[0]=1;var signer=new AssetExportDownloadGrant(original);String t=signer.issue(binding,now).token();original[0]=2;signer.verify(binding,t,now);assertEquals(404,assertThrows(DomainException.class,()->new AssetExportDownloadGrant(original).verify(binding,t,now)).status);assertFalse(signer.toString().contains(t));}
    @Test void clockBeforeIssuanceDoesNotExtendValidity(){var signer=new AssetExportDownloadGrant(key);String t=signer.issue(binding,now).token();assertEquals(404,assertThrows(DomainException.class,()->signer.verify(binding,t,now.minusMillis(1))).status);}
    @Test void smallOrOversizedSecretsRejected(){assertThrows(IllegalArgumentException.class,()->new AssetExportDownloadGrant(new byte[31]));assertThrows(IllegalArgumentException.class,()->new AssetExportDownloadGrant(new byte[65]));}
}
