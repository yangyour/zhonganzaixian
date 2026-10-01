package cn.mop.api;

import cn.mop.domain.*;
import javax.imageio.*;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.*;
import static cn.mop.api.ApiResponse.map;

/** Explicit development direction; no implicit allowed-type default or production approval. */
public final class PhotoPolicy {
    public static final int MAX_SIZE=5000000;
    private final Set<String> allowed;
    public PhotoPolicy(String setting){
        if(setting==null||setting.isBlank()){allowed=null;return;}
        Set<String> types=new TreeSet<>();for(String item:setting.split(",",-1)){String type=item.strip();if(!Set.of("image/jpeg","image/png").contains(type))throw new IllegalStateException("FAIL_CLOSED: unsupported configured photo type");types.add(type);}allowed=Set.copyOf(types);
    }
    public boolean configured(){return allowed!=null;}
    public Map<String,Object> dto(){return map("purpose","customer_report_photo","allowed_media_types",allowed==null?null:new TreeSet<>(allowed),"max_size_bytes",MAX_SIZE,"max_attachments",3,"policy_source","USER_DEVELOPMENT_DIRECTION","production_approval","UNVERIFIED","scan_status","UNSCANNED","retention_until",null);}
    public PhotoUpload validate(String purpose,String name,byte[] bytes){
        if(!"customer_report_photo".equals(purpose))throw invalid("purpose 仅允许 customer_report_photo");
        if(allowed==null)throw new DomainException(422,"CONFIG_PENDING","照片允许类型未配置，未受理",List.of(map("question_ids",List.of("Q28"))));
        if(bytes==null||bytes.length<1||bytes.length>MAX_SIZE)throw invalid("每张照片必须非空且不超过5000000 bytes");
        if(name==null||name.isBlank()||name.codePointCount(0,name.length())>200||name.contains("/")||name.contains("\\")||name.codePoints().anyMatch(ConfigurationValueValidator::invalidCharacter))throw invalid("文件名必须为安全文件名且不超过200字符");
        String media=null;
        if(bytes.length>=8&&bytes[0]==(byte)137&&bytes[1]==80&&bytes[2]==78&&bytes[3]==71&&bytes[4]==13&&bytes[5]==10&&bytes[6]==26&&bytes[7]==10)media="image/png";
        else if(bytes.length>=3&&bytes[0]==(byte)255&&bytes[1]==(byte)216&&bytes[2]==(byte)255)media="image/jpeg";
        if(media==null||!allowed.contains(media))throw invalid("实际图片类型不在已配置允许类型中");
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw invalid("照片内容无法由成熟图片解码器读取");ImageReader reader=readers.next();
            try{reader.setInput(input,true,true);String format=reader.getFormatName().toLowerCase(Locale.ROOT);if(!Set.of("jpeg","jpg","png").contains(format))throw invalid("照片格式不受支持");int width=reader.getWidth(0),height=reader.getHeight(0);if(width<1||height<1||width>16384||height>16384||(long)width*height>25000000)throw invalid("照片超过测试运行解码安全尺寸");if(reader.read(0)==null)throw invalid("照片内容损坏");}finally{reader.dispose();}
            return new PhotoUpload(name,media,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),bytes);
        }catch(DomainException safe){throw safe;}catch(Exception error){throw invalid("照片内容损坏或无法解码");}
    }
    private static DomainException invalid(String message){return new DomainException(400,"VALIDATION_ERROR",message);}
}
