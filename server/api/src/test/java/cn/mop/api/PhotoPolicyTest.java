package cn.mop.api;

import cn.mop.domain.*;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.*;

/** Pure image/input tests: decoder validation does not demonstrate scanning or remote storage. */
class PhotoPolicyTest {
    private final PhotoPolicy policy=new PhotoPolicy("image/jpeg,image/png");
    static byte[] image(String format)throws Exception{var output=new ByteArrayOutputStream();assertTrue(ImageIO.write(new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB),format,output));return output.toByteArray();}
    static byte[] chunk(String type,byte[] content)throws Exception{var output=new ByteArrayOutputStream();output.write(ByteBuffer.allocate(4).putInt(content.length).array());byte[] name=type.getBytes(StandardCharsets.US_ASCII);output.write(name);output.write(content);CRC32 crc=new CRC32();crc.update(name);crc.update(content);output.write(ByteBuffer.allocate(4).putInt((int)crc.getValue()).array());return output.toByteArray();}
    private static void headerCrc(byte[] png){CRC32 crc=new CRC32();crc.update(png,12,17);ByteBuffer.wrap(png,29,4).putInt((int)crc.getValue());}
    private static byte[] encodedJpeg(boolean progressive,int restartInterval)throws Exception{
        var writer=ImageIO.getImageWritersByFormatName("jpeg").next();var image=new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB);var random=new Random(19);for(int y=0;y<128;y++)for(int x=0;x<128;x++)image.setRGB(x,y,random.nextInt(0x1000000));
        var output=new ByteArrayOutputStream();try(var stream=new javax.imageio.stream.MemoryCacheImageOutputStream(output)){writer.setOutput(stream);var parameter=writer.getDefaultWriteParam();if(progressive)parameter.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);var metadata=writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image),parameter);if(restartInterval>0){var tree=(javax.imageio.metadata.IIOMetadataNode)metadata.getAsTree("javax_imageio_jpeg_image_1.0");var sequence=(javax.imageio.metadata.IIOMetadataNode)tree.getElementsByTagName("markerSequence").item(0);var dri=new javax.imageio.metadata.IIOMetadataNode("dri");dri.setAttribute("interval",Integer.toString(restartInterval));sequence.appendChild(dri);metadata.setFromTree("javax_imageio_jpeg_image_1.0",tree);}writer.write(null,new javax.imageio.IIOImage(image,null,metadata),parameter);}finally{writer.dispose();}return output.toByteArray();
    }
    private static boolean pair(byte[] bytes,int first,int second){for(int index=0;index<bytes.length-1;index++)if(Byte.toUnsignedInt(bytes[index])==first&&Byte.toUnsignedInt(bytes[index+1])==second)return true;return false;}

    @Test void jpegAndPngKeepOriginalBytesNamesAndDigestsWithoutAssigningApproval()throws Exception{
        for(String type:List.of("jpeg","png")){byte[] original=image(type);String name="原始照片."+type;PhotoUpload result=policy.validate("customer_report_photo",name,"image/"+type,original);assertEquals(name,result.originalName());assertEquals("image/"+type,result.mediaType());assertArrayEquals(original,result.content());assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)),result.sha256());original[0]=0;assertNotEquals(original[0],result.content()[0]);}
        assertEquals("UNSCANNED",policy.dto().get("scan_status"));assertEquals("UNVERIFIED",policy.dto().get("production_approval"));assertEquals("USER_DEVELOPMENT_DIRECTION",policy.dto().get("policy_source"));assertNull(policy.dto().get("retention_until"));assertEquals(5000000,policy.dto().get("max_size_bytes"));assertEquals(3,policy.dto().get("max_attachments"));
    }
    @Test void missingAllowlistRejectsAdmissionAndUnsupportedConfiguredTypesFailClosed()throws Exception{
        for(String setting:new String[]{null,""," "}){PhotoPolicy absent=new PhotoPolicy(setting);assertFalse(absent.configured());assertNull(absent.dto().get("allowed_media_types"));DomainException error=assertThrows(DomainException.class,()->absent.validate("customer_report_photo","a.png","image/png",image("png")));assertEquals(422,error.status);assertEquals("CONFIG_PENDING",error.code);}
        for(String setting:List.of("image/gif","image/png,","image/jpeg,image/webp"))assertThrows(IllegalStateException.class,()->new PhotoPolicy(setting));
        assertEquals(400,assertThrows(DomainException.class,()->policy.validate("other","a.png","image/png",image("png"))).status);
    }
    @Test void sizeBoundaryAcceptsFiveMillionOriginalBytesAndRejectsOneByteMore()throws Exception{
        byte[] small=image("png"),padding=chunk("mpOt",new byte[PhotoPolicy.MAX_SIZE-small.length-12]);var output=new ByteArrayOutputStream();output.write(small,0,small.length-12);output.write(padding);output.write(small,small.length-12,12);byte[] exact=output.toByteArray();assertEquals(PhotoPolicy.MAX_SIZE,exact.length);assertArrayEquals(exact,policy.validate("customer_report_photo","large.png","image/png",exact).content());
        assertEquals(400,assertThrows(DomainException.class,()->policy.validate("customer_report_photo","large.png","image/png",Arrays.copyOf(exact,exact.length+1))).status);
        for(byte[] empty:new byte[][]{null,new byte[0]})assertThrows(DomainException.class,()->policy.validate("customer_report_photo","a.png","image/png",empty));
    }
    @Test void unsafeFilenamesAreRejectedBeforeTheyReachHeadersOrStorage()throws Exception{
        byte[] png=image("png");for(String name:new String[]{null,"","../a.png","folder/a.png","folder\\a.png","a:\\b.png","a\r\n.png","a\u0000.png","\uD800.png","a\u202e.png"," a.png","a.png ",".png","CON.png","LPT1.png","a\".png","a".repeat(201)+".png"})assertThrows(DomainException.class,()->policy.validate("customer_report_photo",name,"image/png",png),String.valueOf(name));
    }
    @Test void claimedMimeExtensionsAndUnsupportedActualContentCannotMasquerade()throws Exception{
        byte[] png=image("png");for(String declared:new String[]{null,"application/octet-stream"})assertEquals("image/png",policy.validate("customer_report_photo","a.png",declared,png).mediaType());for(String declared:List.of("image/jpeg","application/pdf","image/png;charset=UTF-8"))assertThrows(DomainException.class,()->policy.validate("customer_report_photo","a.png",declared,png));
        for(String name:List.of("a.jpg","a.png.exe","a"))assertThrows(DomainException.class,()->policy.validate("customer_report_photo",name,"image/png",png));
        for(String name:List.of("png","jpg","jpeg")){byte[] original=name.equals("png")?png:image("jpeg");assertThrows(DomainException.class,()->policy.validate("customer_report_photo",name,name.equals("png")?"image/png":"image/jpeg",original));}
        assertThrows(DomainException.class,()->new PhotoPolicy("image/jpeg").validate("customer_report_photo","a.png","image/png",png));
        assertThrows(DomainException.class,()->policy.validate("customer_report_photo","a.jpeg","image/jpeg","not an image".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void truncatedCorruptAndTrailingImagesAreRejected()throws Exception{
        for(String format:List.of("jpeg","png")){byte[] original=image(format);assertThrows(DomainException.class,()->policy.validate("customer_report_photo","a."+format,"image/"+format,Arrays.copyOf(original,original.length-1)));assertThrows(DomainException.class,()->policy.validate("customer_report_photo","a."+format,"image/"+format,Arrays.copyOf(original,original.length+1)));}
        byte[] corrupt=image("png");corrupt[16]^=1;assertThrows(DomainException.class,()->policy.validate("customer_report_photo","a.png","image/png",corrupt));
    }
    @Test void dimensionsAreCheckedBeforeFullDecodeAllocation()throws Exception{
        for(int[] dimensions:new int[][]{{16385,1},{5001,5000},{0,2}}){byte[] png=image("png");ByteBuffer.wrap(png,16,8).putInt(dimensions[0]).putInt(dimensions[1]);headerCrc(png);assertThrows(DomainException.class,()->policy.validate("customer_report_photo","a.png","image/png",png));}
    }
    @Test void jpegRejectsConcatenatedImagesOrJunkFollowedByAnotherEndMarker()throws Exception{
        byte[] original=image("jpeg"),junk=Arrays.copyOf(original,original.length+6);System.arraycopy(new byte[]{'j','u','n','k',(byte)255,(byte)217},0,junk,original.length,6);assertThrows(DomainException.class,()->policy.validate("customer_report_photo","a.jpeg","image/jpeg",junk));
        byte[] concatenated=Arrays.copyOf(original,original.length*2);System.arraycopy(original,0,concatenated,original.length,original.length);assertThrows(DomainException.class,()->policy.validate("customer_report_photo","a.jpeg","image/jpeg",concatenated));
    }
    @Test void jpegMetadataMarkersAndRealStuffedRestartOrProgressiveScansRemainValid()throws Exception{
        byte[] original=image("jpeg");int app0End=4+(Byte.toUnsignedInt(original[4])<<8)+Byte.toUnsignedInt(original[5]);byte[] app={(byte)255,(byte)239,0,10,'M','O',(byte)255,(byte)217,(byte)255,(byte)216,(byte)255,(byte)218};var output=new ByteArrayOutputStream();output.write(original,0,app0End);output.write(app);output.write(original,app0End,original.length-app0End);byte[] metadata=output.toByteArray();assertArrayEquals(metadata,policy.validate("customer_report_photo","metadata.jpeg","image/jpeg",metadata).content());
        for(boolean progressive:List.of(false,true)){byte[] encoded=encodedJpeg(progressive,progressive?0:1);assertTrue(pair(encoded,255,0),"Generated entropy contains real stuffed FF bytes");if(!progressive)assertTrue(pair(encoded,255,208),"Generated restart interval produces RST0");else{int scans=0;for(int index=0;index<encoded.length-1;index++)if(encoded[index]==(byte)255&&encoded[index+1]==(byte)218)scans++;assertTrue(scans>1,"Generated progressive image has multiple SOS markers");}assertArrayEquals(encoded,policy.validate("customer_report_photo","scan.jpeg","image/jpeg",encoded).content());}
    }
    @Test void jpegBoundaryScannerHandlesTemDnlFillAndInvalidLengthsWithoutClaimingDecodeSupport(){
        byte[] structure={(byte)255,(byte)216,(byte)255,(byte)218,0,8,1,1,0,0,63,0,17,(byte)255,0,(byte)217,34,(byte)255,(byte)208,51,(byte)255,1,68,(byte)255,(byte)220,0,4,0,16,85,(byte)255,(byte)255,(byte)217};assertDoesNotThrow(()->PhotoPolicy.jpegStructure(structure));
        for(byte[] malformed:new byte[][]{{(byte)255,(byte)216,(byte)255,(byte)224,0,0},{(byte)255,(byte)216,(byte)255,(byte)224,0,1},{(byte)255,(byte)216,(byte)255,(byte)224,127,(byte)255},{(byte)255,(byte)216,(byte)255},{(byte)255,(byte)216,(byte)255,0},{(byte)255,(byte)216,(byte)255,(byte)216},{(byte)255,(byte)216,(byte)255,(byte)208}})assertThrows(DomainException.class,()->PhotoPolicy.jpegStructure(malformed));
    }
}
