import cn.mop.api.PhotoPolicy;
import cn.mop.domain.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;

public class MopPhotoPolicyCheck {
  static final PhotoPolicy POLICY=new PhotoPolicy("image/jpeg,image/png");
  static int checks;
  static void require(boolean value){checks++;if(!value)throw new AssertionError("Photo policy check failed at "+checks);}
  static void invalid(Runnable operation){boolean rejected=false;try{operation.run();}catch(DomainException error){rejected=error.status==400;}require(rejected);}
  static byte[] image(String type)throws Exception{var out=new ByteArrayOutputStream();require(ImageIO.write(new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB),type,out));return out.toByteArray();}
  static byte[] chunk(String type,byte[] payload)throws Exception{var out=new ByteArrayOutputStream();out.write(ByteBuffer.allocate(4).putInt(payload.length).array());byte[] name=type.getBytes(StandardCharsets.US_ASCII);out.write(name);out.write(payload);CRC32 crc=new CRC32();crc.update(name);crc.update(payload);out.write(ByteBuffer.allocate(4).putInt((int)crc.getValue()).array());return out.toByteArray();}
  static byte[] encodedJpeg(boolean progressive,int restartInterval)throws Exception{
    var writer=ImageIO.getImageWritersByFormatName("jpeg").next();var image=new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB);var random=new Random(19);for(int y=0;y<128;y++)for(int x=0;x<128;x++)image.setRGB(x,y,random.nextInt(0x1000000));var output=new ByteArrayOutputStream();
    try(var stream=new javax.imageio.stream.MemoryCacheImageOutputStream(output)){writer.setOutput(stream);var parameter=writer.getDefaultWriteParam();if(progressive)parameter.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);var metadata=writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image),parameter);if(restartInterval>0){var tree=(javax.imageio.metadata.IIOMetadataNode)metadata.getAsTree("javax_imageio_jpeg_image_1.0");var sequence=(javax.imageio.metadata.IIOMetadataNode)tree.getElementsByTagName("markerSequence").item(0);var dri=new javax.imageio.metadata.IIOMetadataNode("dri");dri.setAttribute("interval",Integer.toString(restartInterval));sequence.appendChild(dri);metadata.setFromTree("javax_imageio_jpeg_image_1.0",tree);}writer.write(null,new javax.imageio.IIOImage(image,null,metadata),parameter);}finally{writer.dispose();}return output.toByteArray();
  }
  static boolean pair(byte[] bytes,int first,int second){for(int index=0;index<bytes.length-1;index++)if(Byte.toUnsignedInt(bytes[index])==first&&Byte.toUnsignedInt(bytes[index+1])==second)return true;return false;}
  public static void main(String[] arguments)throws Exception{
    for(String type:List.of("jpeg","png")){byte[] bytes=image(type);PhotoUpload uploaded=POLICY.validate("customer_report_photo","原图."+type,"image/"+type,bytes);require(Arrays.equals(bytes,uploaded.content()));require(uploaded.sha256().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));invalid(()->POLICY.validate("customer_report_photo","a."+type,"image/"+type,Arrays.copyOf(bytes,bytes.length-1)));invalid(()->POLICY.validate("customer_report_photo","a."+type,"image/"+type,Arrays.copyOf(bytes,bytes.length+1)));}
    byte[] png=image("png");for(String type:new String[]{null,"application/octet-stream"})require(POLICY.validate("customer_report_photo","a.png",type,png).mediaType().equals("image/png"));
    for(String type:List.of("image/jpeg","application/pdf"))invalid(()->POLICY.validate("customer_report_photo","a.png",type,png));
    for(String name:List.of("../a.png","a\\b.png","a\r\n.png","CON.png","a\u202e.png","a.png.exe","a.jpg"))invalid(()->POLICY.validate("customer_report_photo",name,"image/png",png));
    invalid(()->POLICY.validate("customer_report_photo","png","image/png",png));
    byte[] padding=chunk("mpOt",new byte[PhotoPolicy.MAX_SIZE-png.length-12]);var out=new ByteArrayOutputStream();out.write(png,0,png.length-12);out.write(padding);out.write(png,png.length-12,12);byte[] large=out.toByteArray();require(large.length==PhotoPolicy.MAX_SIZE);require(Arrays.equals(large,POLICY.validate("customer_report_photo","large.png","image/png",large).content()));invalid(()->POLICY.validate("customer_report_photo","large.png","image/png",Arrays.copyOf(large,large.length+1)));
    byte[] bomb=png.clone();ByteBuffer.wrap(bomb,16,8).putInt(5001).putInt(5000);CRC32 crc=new CRC32();crc.update(bomb,12,17);ByteBuffer.wrap(bomb,29,4).putInt((int)crc.getValue());invalid(()->POLICY.validate("customer_report_photo","bomb.png","image/png",bomb));
    byte[] corrupt=png.clone();corrupt[16]^=1;invalid(()->POLICY.validate("customer_report_photo","corrupt.png","image/png",corrupt));
    require(new PhotoPolicy(null).dto().get("allowed_media_types")==null);require(POLICY.dto().get("scan_status").equals("UNSCANNED"));require(POLICY.dto().get("production_approval").equals("UNVERIFIED"));require(POLICY.dto().get("retention_until")==null);
    byte[] jpeg=image("jpeg"),junk=Arrays.copyOf(jpeg,jpeg.length+6);System.arraycopy(new byte[]{'j','u','n','k',(byte)255,(byte)217},0,junk,jpeg.length,6);invalid(()->POLICY.validate("customer_report_photo","a.jpeg","image/jpeg",junk));byte[] concatenated=Arrays.copyOf(jpeg,jpeg.length*2);System.arraycopy(jpeg,0,concatenated,jpeg.length,jpeg.length);invalid(()->POLICY.validate("customer_report_photo","a.jpeg","image/jpeg",concatenated));
    for(String name:List.of("jpg","jpeg"))invalid(()->POLICY.validate("customer_report_photo",name,"image/jpeg",jpeg));
    int app0End=4+(Byte.toUnsignedInt(jpeg[4])<<8)+Byte.toUnsignedInt(jpeg[5]);byte[] app={(byte)255,(byte)239,0,10,'M','O',(byte)255,(byte)217,(byte)255,(byte)216,(byte)255,(byte)218};var withApp=new ByteArrayOutputStream();withApp.write(jpeg,0,app0End);withApp.write(app);withApp.write(jpeg,app0End,jpeg.length-app0End);byte[] metadata=withApp.toByteArray();PhotoUpload originalMetadata=POLICY.validate("customer_report_photo","metadata.jpeg","image/jpeg",metadata);require(Arrays.equals(metadata,originalMetadata.content()));require(originalMetadata.sha256().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(metadata))));
    for(boolean progressive:List.of(false,true)){byte[] encoded=encodedJpeg(progressive,progressive?0:1);require(pair(encoded,255,0));if(!progressive)require(pair(encoded,255,208));else{int scans=0;for(int index=0;index<encoded.length-1;index++)if(encoded[index]==(byte)255&&encoded[index+1]==(byte)218)scans++;require(scans>1);}PhotoUpload validated=POLICY.validate("customer_report_photo","scan.jpeg","image/jpeg",encoded);require(Arrays.equals(encoded,validated.content()));require(validated.sha256().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded))));}
    for(byte[] malformed:new byte[][]{{(byte)255,(byte)216,(byte)255,(byte)224,0,0},{(byte)255,(byte)216,(byte)255,(byte)224,0,1},{(byte)255,(byte)216,(byte)255,(byte)224,127,(byte)255},{(byte)255,(byte)216,(byte)255},{(byte)255,(byte)216,(byte)255,0},{(byte)255,(byte)216,(byte)255,(byte)216},{(byte)255,(byte)216,(byte)255,(byte)208}})invalid(()->POLICY.validate("customer_report_photo","broken.jpeg","image/jpeg",malformed));
    byte[] structure={(byte)255,(byte)216,(byte)255,(byte)218,0,8,1,1,0,0,63,0,17,(byte)255,0,(byte)217,34,(byte)255,(byte)208,51,(byte)255,1,68,(byte)255,(byte)220,0,4,0,16,85,(byte)255,(byte)255,(byte)217};var boundary=PhotoPolicy.class.getDeclaredMethod("jpegStructure",byte[].class);boundary.setAccessible(true);boundary.invoke(null,(Object)structure);require(true);invalid(()->POLICY.validate("customer_report_photo","structure-only.jpeg","image/jpeg",structure));
    System.out.println("Pure JDK photo policy harness passed: "+checks+" assertions. No Maven/JUnit or remote integration claim.");
  }
}
