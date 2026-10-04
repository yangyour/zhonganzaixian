package cn.mop.domain.assetexport;

import cn.mop.domain.DomainException;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Bounded opaque prepared bytes. Consistency binding, not XLSX validation, current authority or publication. */
public final class AssetExportPreparedFile {
    private final String commandFingerprint;private final AssetExportJobState.Encoding encoding;private final byte[] bytes;
    private AssetExportPreparedFile(String commandFingerprint,AssetExportJobState.Encoding encoding,byte[] bytes){this.commandFingerprint=commandFingerprint;this.encoding=encoding;this.bytes=bytes;}
    public static AssetExportPreparedFile create(AssetExportJobState.Command command,String selectionSha256,int rows,Instant generatedAt,byte[] input){
        if(command==null||input==null||input.length==0)throw invalid();if(input.length>AssetExportJobState.MAX_FILE_BYTES)throw new DomainException(413,"EXPORT_ENCODING_LIMIT","Prepared export exceeds the technical byte limit");
        byte[] owned=input.clone();var encoding=new AssetExportJobState.Encoding(selectionSha256,hash(owned),owned.length,rows,generatedAt);return new AssetExportPreparedFile(command.fingerprint(),encoding,owned);
    }
    public static AssetExportPreparedFile restore(AssetExportJobState.Command command,AssetExportJobState.Encoding expected,byte[] input){
        if(expected==null)throw invalid();var value=create(command,expected.selectionSha256(),expected.rowCount(),expected.generatedAt(),input);if(!value.encoding.equals(expected))throw new DomainException(409,"EXPORT_PREPARED_MISMATCH","Prepared bytes do not match retained encoding facts");return value;
    }
    public String commandFingerprint(){return commandFingerprint;}
    public AssetExportJobState.Encoding encoding(){return encoding;}
    public byte[] bytes(){return bytes.clone();}
    public boolean sameContent(AssetExportPreparedFile other){return other!=null&&commandFingerprint.equals(other.commandFingerprint)&&encoding.equals(other.encoding)&&MessageDigest.isEqual(bytes,other.bytes);}
    public boolean published(){return false;}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","Explicit bounded prepared export bytes and descriptor required");}
}
