package cn.mop.domain.notification;
import cn.mop.domain.DomainException;
import cn.mop.domain.contact.CustomerContact.QuietWindow;
import cn.mop.domain.notification.DeliveryReceiptState.Channel;
import cn.mop.domain.time.BusinessDates;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
/** Explicit owner-scoped preference requests only. Current membership, receiver binding and effective routing are separate gates. */
public record OwnNotificationPreferenceDraft(UUID tenantId,UUID ownerId,LevelPreference requestedLevel,
                                             List<Channel> requestedChannels,QuietWindow requestedQuietWindow) {
    public enum LevelPreference { P1_ONLY,P1_P2,ALL }
    public static final String FORMAT="MOP_OWN_NOTIFICATION_PREFERENCE_DRAFT_V1";
    public OwnNotificationPreferenceDraft {
        if(tenantId==null||ownerId==null)throw invalid();
        if(requestedChannels!=null){
            if(requestedChannels.size()>Channel.values().length||requestedChannels.stream().anyMatch(Objects::isNull)||new HashSet<>(requestedChannels).size()!=requestedChannels.size())throw invalid();
            var channels=new ArrayList<>(requestedChannels);channels.sort(Comparator.comparing(Enum::name));requestedChannels=List.copyOf(channels);
        }
        if(requestedQuietWindow!=null&&!BusinessDates.ZONE.getId().equals(requestedQuietWindow.zone()))throw invalid();
    }
    public String timezone(){return BusinessDates.ZONE.getId();}
    public String recordSource(){return "OWN_NOTIFICATION_PREFERENCE_DRAFT";}
    public String configurationStatus(){return "CONFIG_PENDING";}
    public boolean effective(){return false;}
    public LevelPreference effectiveLevelPreference(){return null;}
    public List<Channel> effectiveChannels(){return null;}
    public QuietWindow effectiveQuietWindow(){return null;}
    public UUID verifiedReceiverId(){return null;}
    public String receiverBindingStatus(){return "NOT_VERIFIED";}
    public Boolean deliveryDecision(){return null;}
    public boolean notificationDispatched(){return false;}
    /** M8-10/Q14 contract fact only. A preference does not resolve priority mapping or authorize sending. */
    public boolean p1QuietBypassIsContractRule(){return true;}
    public byte[] canonicalBytes(){
        try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){
            field(out,FORMAT);field(out,tenantId.toString());field(out,ownerId.toString());field(out,requestedLevel==null?null:requestedLevel.name());
            if(requestedChannels==null)out.writeInt(-1);else{out.writeInt(requestedChannels.size());for(var channel:requestedChannels)field(out,channel.name());}
            if(requestedQuietWindow==null)out.writeInt(-1);else{out.writeInt(3);field(out,requestedQuietWindow.enabled()==null?null:requestedQuietWindow.enabled().toString());field(out,requestedQuietWindow.start()==null?null:requestedQuietWindow.start().toString());field(out,requestedQuietWindow.end()==null?null:requestedQuietWindow.end().toString());}
            field(out,timezone());out.flush();return bytes.toByteArray();
        }catch(IOException impossible){throw new IllegalStateException("Preference draft encoding unavailable",impossible);}
    }
    public String fingerprint(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes()));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}}
    private static void field(DataOutputStream out,String value)throws IOException{if(value==null){out.writeInt(-1);return;}byte[] data=value.getBytes(StandardCharsets.UTF_8);out.writeInt(data.length);out.write(data);}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","本人通知偏好草稿范围或明确渠道无效；不代替接收人绑定、矩阵批准或真实派发");}
}
