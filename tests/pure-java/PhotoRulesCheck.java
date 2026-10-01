import cn.mop.domain.*;
import java.security.MessageDigest;
import java.util.*;
public final class PhotoRulesCheck {
  public static void main(String[] args)throws Exception {
    byte[] bytes={1,2,3};String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));PhotoUpload photo=new PhotoUpload("photo.png","image/png",hash,bytes);bytes[0]=9;photo.content()[0]=7;
    if(!Arrays.equals(new byte[]{1,2,3},photo.content()))throw new AssertionError("mutable original bytes");PhotoRules.validateUpload(photo);
    int count=2;
    try{PhotoRules.validateStoredContent(new byte[]{1},hash);throw new AssertionError("mismatched hash accepted");}catch(DomainException error){if(!"FILE_INTEGRITY_CONFLICT".equals(error.code))throw error;count++;}
    try{PhotoRules.validateUpload(new PhotoUpload("../photo.png","image/png",hash,photo.content()));throw new AssertionError("unsafe name accepted");}catch(DomainException error){if(error.status!=400)throw error;count++;}
    try{PhotoRules.validateStoredContent(new byte[5000001],hash);throw new AssertionError("oversize accepted");}catch(DomainException error){if(error.status!=400)throw error;count++;}
    String a="55000000-0000-4000-8000-000000000001",b="55000000-0000-4000-8000-000000000002";if(!PhotoRules.attachmentIds(List.of(b,a,b)).equals(List.of(a,b)))throw new AssertionError("unstable lock order");count++;
    try{PhotoRules.attachmentIds(List.of(a,a,a,a));throw new AssertionError("limit bypassed");}catch(DomainException error){if(error.status!=400)throw error;count++;}
    if(PhotoRules.retrySeconds(1)!=5||PhotoRules.retrySeconds(Integer.MAX_VALUE)!=300||PhotoRules.retrySeconds(Integer.MIN_VALUE)!=5)throw new AssertionError("retry bounds");count++;
    System.out.println("PhotoRules pure JDK assertions passed: "+count+"; no component integration executed");
  }
}
