package cn.mop.domain.assetexport;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetexport.AssetExportJobState.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;

/** Versioned structural codec, not a MAC, signature, database identity check or permission grant. */
public final class AssetExportCommandCodec {
    private AssetExportCommandCodec() {}
    public static final int MAX_COMMAND_BYTES=4096, MAX_FIELD_BYTES=512;
    private static final String MAGIC="mop-asset-export-command-v1";
    public static byte[] encode(Command command){
        if(command==null)throw invalid();
        var a=command.acceptedAccess();var f=command.filters();
        try(var bytes=new ByteArrayOutputStream();var output=new DataOutputStream(bytes)){
            for(String value:Arrays.asList(MAGIC,a.tenantId().toString(),a.actorId().toString(),Long.toString(a.permissionVersion()),a.role(),f.type(),f.status(),f.siteId(),f.query())){
                if(value==null)output.writeInt(-1);else{byte[] text=value.getBytes(StandardCharsets.UTF_8);if(text.length>MAX_FIELD_BYTES)throw invalid();output.writeInt(text.length);output.write(text);}
            }
            if(bytes.size()>MAX_COMMAND_BYTES)throw limit();return bytes.toByteArray();
        }catch(IOException impossible){throw new IllegalStateException(impossible);}
    }
    public static Command decode(byte[] input){
        if(input==null||input.length==0)throw invalid();if(input.length>MAX_COMMAND_BYTES)throw limit();
        byte[] owned=input.clone();
        try(var stream=new DataInputStream(new ByteArrayInputStream(owned))){
            if(!MAGIC.equals(field(stream)))throw invalid();
            String tenant=field(stream),actor=field(stream),permission=field(stream),role=field(stream);
            if(tenant==null||actor==null||permission==null||role==null)throw invalid();
            var filters=new Filters(field(stream),field(stream),field(stream),field(stream));
            if(stream.available()!=0)throw invalid();
            var command=new Command(new Access(UUID.fromString(tenant),UUID.fromString(actor),Long.parseLong(permission),role,true),filters);
            // Reject alternate UUID/number spellings, unexpected nulls and any noncanonical round trip.
            if(!MessageDigest.isEqual(owned,encode(command)))throw invalid();return command;
        }catch(IOException|IllegalArgumentException|DomainException malformed){throw invalid();}
    }
    /** Binds structural bytes to immutable persisted acceptance columns, not current membership authorization. */
    public static Command decodeBound(byte[] input,Access persistedAcceptance,String expectedSha256){
        if(persistedAcceptance==null||expectedSha256==null||!expectedSha256.matches("[0-9a-f]{64}"))throw invalid();
        Command decoded=decode(input);Access a=decoded.acceptedAccess();
        if(!Objects.equals(a.tenantId(),persistedAcceptance.tenantId())||!Objects.equals(a.actorId(),persistedAcceptance.actorId()))
            throw new DomainException(404,"OBJECT_NOT_FOUND","Export not found");
        if(!a.equals(persistedAcceptance)||!MessageDigest.isEqual(HexFormat.of().parseHex(expectedSha256),HexFormat.of().parseHex(decoded.fingerprint())))
            throw new DomainException(409,"EXPORT_COMMAND_BINDING_CONFLICT","Export command differs from retained acceptance facts");
        return decoded;
    }
    private static String field(DataInputStream stream)throws IOException{
        int length=stream.readInt();if(length==-1)return null;if(length<0||length>MAX_FIELD_BYTES||length>stream.available())throw invalid();
        byte[] bytes=stream.readNBytes(length);if(bytes.length!=length)throw invalid();
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static DomainException invalid(){return new DomainException(400,"EXPORT_COMMAND_INVALID","Invalid canonical export command bytes; no fields were logged");}
    private static DomainException limit(){return new DomainException(413,"EXPORT_COMMAND_LIMIT","Export command exceeds technical byte limit");}
}
