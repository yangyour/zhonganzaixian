package cn.mop.adapters.backup;

import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupArchivePayload;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BackupArchivePayloadTest {
 byte[] vector() throws Exception {return HexFormat.of().parseHex(BackupEnvelopeEncryptionTest.vector().path("envelope_hex").asText());}
 BackupArchivePayload parse(byte[] b) throws Exception{return BackupArchivePayload.parse(b,BackupEnvelopeEncryptionTest.sha(b));}
 void rejected(byte[] b) throws Exception {String hash=BackupEnvelopeEncryptionTest.sha(b);assertEquals(400,assertThrows(DomainException.class,()->BackupArchivePayload.parse(b,hash)).status);}
 @Test void independentVectorRetainsExactScopeAndBytesWithoutAuthenticationClaims()throws Exception{
  byte[] b=vector();var p=parse(b);assertEquals(351,p.size());assertEquals(BackupEnvelopeEncryptionTest.TENANT,p.scope().tenantId());assertEquals(BackupEnvelopeEncryptionTest.ASSET,p.scope().assetId());assertEquals(BackupEnvelopeEncryptionTest.RUN,p.scope().runId());assertEquals(BackupEnvelopeEncryptionTest.VERSION,p.scope().versionId());assertEquals(1,p.scope().attemptNumber());assertEquals(BackupEnvelopeEncryptionTest.REF,p.keyReference());assertArrayEquals(b,p.bytes());assertFalse(p.ciphertextAuthenticationVerified());assertFalse(p.deviceExecutionAuthorized());
 }
 @Test void callerCannotMutateDurableSource()throws Exception{byte[] b=vector(),expected=b.clone();var p=parse(b);b[0]^=1;byte[] returned=p.bytes();returned[0]^=1;assertArrayEquals(expected,p.bytes());assertFalse(p.toString().contains(p.sha256()));assertFalse(p.toString().contains(p.keyReference()));}
 @Test void transportDigestCannotBeUsedAsAuthentication()throws Exception{byte[] b=vector();b[b.length-1]^=1;var p=parse(b);assertFalse(p.ciphertextAuthenticationVerified());var c=BackupEnvelopeEncryptionTest.cipher(new BackupEnvelopeEncryptionTest.Provider());var envelope=c.decode(p.bytes(),p.sha256(),BackupEnvelopeEncryptionTest.SCOPE);assertThrows(DomainException.class,()->c.open(BackupEnvelopeEncryptionTest.ACCESS,BackupEnvelopeEncryptionTest.SCOPE,envelope));}
 @Test void hashRequiredAndCanonicalLowercase()throws Exception{byte[] b=vector();for(String hash:new String[]{"", "0".repeat(64),BackupEnvelopeEncryptionTest.sha(b).toUpperCase(Locale.ROOT)})assertThrows(DomainException.class,()->BackupArchivePayload.parse(b,hash));assertThrows(DomainException.class,()->BackupArchivePayload.parse(b,null));assertThrows(DomainException.class,()->BackupArchivePayload.parse(null,"0".repeat(64)));}
 @Test void everyTruncationAndTrailingByteRejected()throws Exception{byte[] b=vector();for(int n=0;n<b.length;n++)rejected(Arrays.copyOf(b,n));rejected(Arrays.copyOf(b,b.length+1));}
 @Test void boundedDeclaredLengthsRejectedBeforeAllocation()throws Exception{byte[] b=vector();int wrapped=195+2+BackupEnvelopeEncryptionTest.REF.length();for(int n:new int[]{-1,0,8193,Integer.MAX_VALUE}){byte[] x=b.clone();ByteBuffer.wrap(x).putInt(wrapped,n);rejected(x);}int cipher=b.length-64-4;for(int n:new int[]{-1,0,15,BackupArchivePayload.MAX_PLAINTEXT_BYTES+17,Integer.MAX_VALUE}){byte[] x=b.clone();ByteBuffer.wrap(x).putInt(cipher,n);rejected(x);}rejected(new byte[BackupArchivePayload.MAX_ENVELOPE_BYTES+1]);}
 @Test void attemptAndFormatCannotBeCoerced()throws Exception{byte[] b=vector();for(int n:new int[]{-1,0,4,Integer.MAX_VALUE}){byte[] x=b.clone();ByteBuffer.wrap(x).putInt(153,n);rejected(x);}byte[] x=b.clone();x[2]^=1;rejected(x);x=b.clone();x[28]^=1;rejected(x);}
 @Test void shortUuidAcceptedByJdkIsNotCanonicalWireFormat()throws Exception{byte[] b=vector();try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){out.write(b,0,39);out.writeUTF("1-1-1-1-1");out.write(b,77,b.length-77);rejected(bytes.toByteArray());}}
 @Test void nonCanonicalModifiedUtfIsRejected()throws Exception{byte[] b=vector();byte[] x=new byte[b.length+1];x[0]=0;x[1]=25;x[2]=(byte)0xc1;x[3]=(byte)0x8d;System.arraycopy(b,3,x,4,b.length-3);rejected(x);}
 @Test void scopeRequiresCompleteIdentityAndSupportedAttempt(){assertThrows(DomainException.class,()->new BackupArchivePayload.Scope(null,UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID()));assertThrows(DomainException.class,()->new BackupArchivePayload.Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),0,UUID.randomUUID()));}
}
