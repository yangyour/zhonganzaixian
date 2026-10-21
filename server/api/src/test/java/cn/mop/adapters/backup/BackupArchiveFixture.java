package cn.mop.adapters.backup;
import cn.mop.adapters.backup.BackupEnvelopeEncryption.*;
import java.util.*;
/** Public synthetic test bytes only. Never registered as a production provider. */
public final class BackupArchiveFixture {
 public static final UUID TENANT=UUID.fromString("79000000-0000-4000-8000-000000000001"),ASSET=UUID.fromString("79000000-0000-4000-8000-000000000002"),RUN=UUID.fromString("79000000-0000-4000-8000-000000000003");
 public static final Access ACCESS=new Access(TENANT,UUID.randomUUID(),1);
 public static final byte[] PLAIN="SYNTHETIC archive configuration; not a real device".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
 public static Scope scope(){return new Scope(TENANT,ASSET,RUN,1,UUID.randomUUID());}
 public static class Keys implements DataKeyProvider {public int creates,unwraps;private KeyMaterial material(String ref,byte[] wrapped){byte[] key=new byte[32];for(int i=0;i<32;i++)key[i]=(byte)i;return new KeyMaterial(ref,wrapped,key);}public KeyMaterial create(Access a,Scope s){creates++;return material("kms-key-ref:synthetic-archive","SYNTHETIC-WRAPPED-NOT-KMS".getBytes());}public KeyMaterial unwrap(Access a,Scope s,String ref,byte[] wrapped){unwraps++;return material(ref,wrapped);}}
 public static BackupEnvelopeEncryption crypto(Keys keys){return new BackupEnvelopeEncryption(keys,(a,s,o)->true,1024);}
}
