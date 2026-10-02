package cn.mop.api;

import cn.mop.domain.DomainException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Pre-generated trusted contract template, never a parser or object-storage request. */
final class AssetImportTemplateFile {
    static final String NAME="asset-import-standard15.xlsx";
    static final String MEDIA_TYPE="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String SHA256="8bf810106054cacd60e41e47848d06d677cf62622fa3c5109c8040c66088932c";
    static final int SIZE=4573;
    private AssetImportTemplateFile(){}
    static byte[] read(){
        try(var input=AssetImportTemplateFile.class.getResourceAsStream("/templates/"+NAME)){
            if(input==null)throw unavailable();return verified(input.readNBytes(SIZE+1));
        }catch(Exception failure){throw unavailable();}
    }
    static byte[] verified(byte[] content){
        try{if(content==null||content.length!=SIZE||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))))throw unavailable();return content.clone();}
        catch(Exception failure){throw unavailable();}
    }
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","标准空模板完整性未核验，未返回下载文件");}
}
