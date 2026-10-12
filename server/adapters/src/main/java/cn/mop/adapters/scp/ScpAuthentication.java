package cn.mop.adapters.scp;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;

/** Explicit vendor signing profile, not the standard AWS host/query signing algorithm. */
public final class ScpAuthentication {
    private ScpAuthentication(){}
    public static String encryptPassword(char[] password,String modulus){
        byte[] clear=null;
        try{
            if(password==null||password.length==0||password.length>4096||modulus==null)throw ScpProtocol.fail("INVALID_PASSWORD_OR_KEY");
            String hex=modulus.endsWith("\n")?modulus.substring(0,modulus.length()-1):modulus;
            if(!hex.matches("[0-9A-Fa-f]{256,2048}")||hex.length()%2!=0)throw ScpProtocol.fail("INVALID_RSA_MODULUS");
            BigInteger n=new BigInteger(hex,16);if(n.bitLength()<1024||n.bitLength()>8192||!n.testBit(0))throw ScpProtocol.fail("INVALID_RSA_MODULUS");
            var encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(password));
            clear=new byte[encoded.remaining()];encoded.get(clear);if(encoded.hasArray())Arrays.fill(encoded.array(),(byte)0);
            if(clear.length>(n.bitLength()+7)/8-11)throw ScpProtocol.fail("PASSWORD_TOO_LONG_FOR_KEY");
            PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n,BigInteger.valueOf(65537)));
            Cipher cipher=Cipher.getInstance("RSA/ECB/PKCS1Padding");cipher.init(Cipher.ENCRYPT_MODE,key,new SecureRandom());
            return HexFormat.of().formatHex(cipher.doFinal(clear));
        }catch(ScpProtocol.Failure failure){throw failure;}catch(Exception failure){throw ScpProtocol.fail("RSA_ENCRYPTION_FAILED");}
        finally{if(clear!=null)Arrays.fill(clear,(byte)0);}
    }
    public static final class Token {
        private final String value;private final Instant issuedAt,expiresAt;
        private Token(String value,Instant issuedAt,Instant expiresAt){this.value=value;this.issuedAt=issuedAt;this.expiresAt=expiresAt;}
        public boolean usableAt(Instant now){return !now.isBefore(issuedAt)&&now.isBefore(expiresAt.minusSeconds(30));}
        public String authorization(Instant now){if(!usableAt(now))throw ScpProtocol.fail("TOKEN_EXPIRED_OR_CLOCK_SKEW");return "Token "+value;}
        public Instant expiresAt(){return expiresAt;}
        @Override public String toString(){return "ScpToken[REDACTED]";}
    }
    public static Token token(JsonNode data,Instant now){
        try{
            JsonNode node=data.path("access").path("token");String value=ScpProtocol.requiredText(node,"id",2048);
            if(!value.matches("[A-Za-z0-9._~-]{1,2048}"))throw ScpProtocol.fail("INVALID_TOKEN");
            Instant issued=Instant.parse(ScpProtocol.requiredText(node,"issued_at",64)),expires=Instant.parse(ScpProtocol.requiredText(node,"expires",64));
            if(!expires.isAfter(issued))throw ScpProtocol.fail("INVALID_TOKEN_TIME");Token token=new Token(value,issued,expires);
            if(!token.usableAt(now))throw ScpProtocol.fail("TOKEN_EXPIRED_OR_CLOCK_SKEW");return token;
        }catch(ScpProtocol.Failure failure){throw failure;}catch(Exception failure){throw ScpProtocol.fail("INVALID_TOKEN_RESPONSE");}
    }
    /** PDF p10-11 profile: empty canonical query, signed headers path;x-amz-date.
     * The service/region must be supplied explicitly; PDF examples disagree on service names.
     */
    public static Map<String,String> ec2(ScpProtocol.Request request,String accessKey,char[] secretKey,String region,String service,Instant at){
        if(accessKey==null||!accessKey.matches("[A-Za-z0-9_-]{1,128}")||secretKey==null||secretKey.length==0||secretKey.length>512
            ||region==null||!region.matches("[a-z0-9-]{1,64}")||service==null||!service.matches("[a-z0-9-]{1,64}"))throw ScpProtocol.fail("INVALID_EC2_CONFIGURATION");
        for(char c:secretKey)if(c<33||c>126)throw ScpProtocol.fail("INVALID_EC2_CONFIGURATION");
        byte[] signing=null;
        try{
            String date=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(at),day=date.substring(0,8);
            String scope=day+"/"+region+"/"+service+"/aws4_request",signed="path;x-amz-date";
            String canonical=String.join("\n",request.method(),request.path(),"","x-amz-date:"+date+"\n",signed,sha(request.body()));
            String toSign=String.join("\n","AWS4-HMAC-SHA256",date,scope,sha(canonical.getBytes(StandardCharsets.UTF_8)));
            signing=("AWS4"+new String(secretKey)).getBytes(StandardCharsets.UTF_8);
            for(String part:List.of(day,region,service,"aws4_request")){byte[] next=hmac(signing,part);Arrays.fill(signing,(byte)0);signing=next;}
            String signature=HexFormat.of().formatHex(hmac(signing,toSign));
            return Map.of("Authorization","AWS4-HMAC-SHA256 Credential="+accessKey+"/"+scope+", SignedHeaders="+signed+", Signature="+signature,"X-Amz-Date",date);
        }catch(ScpProtocol.Failure failure){throw failure;}catch(Exception failure){throw ScpProtocol.fail("EC2_SIGNING_FAILED");}
        finally{if(signing!=null)Arrays.fill(signing,(byte)0);}
    }
    private static byte[] hmac(byte[] key,String text)throws GeneralSecurityException{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));}
    private static String sha(byte[] bytes)throws GeneralSecurityException{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
