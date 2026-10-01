package cn.mop.api;

import cn.mop.domain.*;
import javax.imageio.*;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

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
    public PhotoUpload validate(String purpose,String name,String declaredMediaType,byte[] bytes){
        if(!"customer_report_photo".equals(purpose))throw invalid("purpose 仅允许 customer_report_photo");
        if(allowed==null)throw new DomainException(422,"CONFIG_PENDING","照片允许类型未配置，未受理",List.of(map("question_ids",List.of("Q28"))));
        if(bytes==null||bytes.length<1||bytes.length>MAX_SIZE)throw invalid("每张照片必须非空且不超过5000000 bytes");
        filename(name);
        String media=null;
        if(bytes.length>=8&&bytes[0]==(byte)137&&bytes[1]==80&&bytes[2]==78&&bytes[3]==71&&bytes[4]==13&&bytes[5]==10&&bytes[6]==26&&bytes[7]==10)media="image/png";
        else if(bytes.length>=3&&bytes[0]==(byte)255&&bytes[1]==(byte)216&&bytes[2]==(byte)255)media="image/jpeg";
        if(media==null||!allowed.contains(media))throw invalid("实际图片类型不在已配置允许类型中");
        if(declaredMediaType!=null&&!declaredMediaType.equals("application/octet-stream")&&!media.equals(declaredMediaType))throw invalid("声明图片类型与原图内容不一致");
        int lastDot=name.lastIndexOf('.');if(lastDot<1||lastDot==name.length()-1)throw invalid("文件名必须包含匹配原图类型的扩展名");
        String extension=name.substring(lastDot+1).toLowerCase(Locale.ROOT);
        if(!(media.equals("image/png")?extension.equals("png"):Set.of("jpg","jpeg").contains(extension)))throw invalid("文件扩展名与原图内容不一致");
        if(media.equals("image/png"))pngStructure(bytes);
        else jpegStructure(bytes);
        try(var input=new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw invalid("照片内容无法由成熟图片解码器读取");ImageReader reader=readers.next();
            try{AtomicBoolean warning=new AtomicBoolean();reader.addIIOReadWarningListener((source,message)->warning.set(true));reader.setInput(input,true,true);String format=reader.getFormatName().toLowerCase(Locale.ROOT);if(!(media.equals("image/png")?format.equals("png"):Set.of("jpeg","jpg").contains(format)))throw invalid("照片格式不受支持");int width=reader.getWidth(0),height=reader.getHeight(0);if(width<1||height<1||width>16384||height>16384||(long)width*height>25000000)throw invalid("照片超过测试运行解码安全尺寸");if(reader.read(0)==null||warning.get())throw invalid("照片内容损坏");}finally{reader.dispose();}
            // Decode validates the image; it is not malware scanning. Preserve the original bytes.
            return new PhotoUpload(name,media,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),bytes.clone());
        }catch(DomainException safe){throw safe;}catch(Exception error){throw invalid("照片内容损坏或无法解码");}
    }
    static void filename(String name){
        if(name==null||name.isBlank()||!name.equals(name.strip())||name.codePointCount(0,name.length())>200||name.startsWith(".")||name.endsWith(".")||name.codePoints().anyMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.SURROGATE||Character.getType(cp)==Character.FORMAT||"/\\:<>\"|?*".indexOf(cp)>=0))throw invalid("文件名必须为安全文件名且不超过200字符");
        String stem=name.substring(0,name.indexOf('.')<0?name.length():name.indexOf('.')).toUpperCase(Locale.ROOT);
        if(Set.of("CON","PRN","AUX","NUL").contains(stem)||stem.matches("(?:COM|LPT)[1-9]"))throw invalid("文件名为保留名称");
    }
    /** Codestream boundary validation only. Segment payloads can contain marker-like bytes;
     * entropy bytes use stuffing/restart markers. ImageIO still validates decoded image data. */
    static void jpegStructure(byte[] bytes){
        int offset=2;boolean entropy=false,scan=false;
        while(offset<bytes.length){
            boolean wasEntropy=entropy;
            if(entropy){while(offset<bytes.length&&bytes[offset]!=(byte)255)offset++;}
            else if(bytes[offset]!=(byte)255)throw invalid("JPEG 标记结构无效");
            if(offset==bytes.length)throw invalid("JPEG 原图不完整");
            do{offset++;}while(offset<bytes.length&&bytes[offset]==(byte)255);
            if(offset==bytes.length)throw invalid("JPEG 原图不完整");
            int marker=Byte.toUnsignedInt(bytes[offset++]);
            if(marker==0){if(entropy)continue;throw invalid("JPEG 标记结构无效");}
            if(marker>=0xd0&&marker<=0xd7){if(entropy)continue;throw invalid("JPEG 重启标记位置无效");}
            if(marker==1)continue; // Standalone TEM marker; never interpret a segment length.
            entropy=false;
            if(marker==0xd9){if(!scan||offset!=bytes.length)throw invalid("JPEG 原图不完整或包含尾随内容");return;}
            if(marker==0xd8)throw invalid("JPEG 原图含重复开始标记");
            if(bytes.length-offset<2)throw invalid("JPEG 数据段不完整");
            int length=(Byte.toUnsignedInt(bytes[offset])<<8)|Byte.toUnsignedInt(bytes[offset+1]);
            if(length<2||length>bytes.length-offset||marker==0xdc&&length!=4)throw invalid("JPEG 数据段长度无效");
            offset+=length;
            if(marker==0xda){entropy=true;scan=true;}
            else if(wasEntropy&&marker==0xdc)entropy=true; // DNL defines height within a scan.
        }
        throw invalid("JPEG 原图缺少结束标记");
    }
    private static void pngStructure(byte[] bytes){
        int offset=8;boolean header=false,data=false;
        while(offset<bytes.length){
            if(bytes.length-offset<12)throw invalid("PNG 原图不完整");
            long length=Integer.toUnsignedLong(ByteBuffer.wrap(bytes,offset,4).getInt());
            if(length>bytes.length-offset-12)throw invalid("PNG 数据块长度无效");
            int count=(int)length;String type=new String(bytes,offset+4,4,java.nio.charset.StandardCharsets.US_ASCII);
            if(!type.matches("[A-Za-z]{4}")||!header&&(!type.equals("IHDR")||count!=13)||header&&type.equals("IHDR"))throw invalid("PNG 数据块结构无效");
            CRC32 crc=new CRC32();crc.update(bytes,offset+4,count+4);
            if(crc.getValue()!=Integer.toUnsignedLong(ByteBuffer.wrap(bytes,offset+8+count,4).getInt()))throw invalid("PNG 数据块校验失败");
            header=true;if(type.equals("IDAT"))data=true;offset+=count+12;
            if(type.equals("IEND")){if(count!=0||!data||offset!=bytes.length)throw invalid("PNG 原图不完整或包含尾随内容");return;}
        }
        throw invalid("PNG 原图缺少结束标记");
    }
    private static DomainException invalid(String message){return new DomainException(400,"VALIDATION_ERROR",message);}
    private static Map<String,Object> map(Object... pairs){Map<String,Object> values=new LinkedHashMap<>();for(int index=0;index<pairs.length;index+=2)values.put(pairs[index].toString(),pairs[index+1]);return values;}
}
