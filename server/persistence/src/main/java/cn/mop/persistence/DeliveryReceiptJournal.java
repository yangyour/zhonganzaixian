package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.DeliveryReceiptState;
import cn.mop.domain.notification.DeliveryReceiptState.*;
import cn.mop.persistence.DeliveryReceiptSnapshotCodec.Scope;
import java.io.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Bounded append-only storage representation. No database, authentication, dispatch or replay permission.
 * One small command per observation, not a full historical snapshot per observation.
 * Digests detect accidental corruption/scope substitution, not malicious rewriting or provider forgery. */
public final class DeliveryReceiptJournal {
    public static final String FORMAT = "MOP_DELIVERY_RECEIPT_JOURNAL_V1";
    public static final int MAX_ENTRY_BYTES = 1024;
    public static final int MAX_ENTRIES = DeliveryReceiptSnapshotCodec.MAX_TRANSITIONS;
    private static final long MAX_PERMISSION_VERSION = 9_007_199_254_740_991L;
    private static final DeliveryReceiptSnapshotCodec SNAPSHOTS = new DeliveryReceiptSnapshotCodec();

    public record Command(long expectedVersion, UUID commandId, UUID actorId, long permissionVersion,
                          String traceId, Transition transition) {
        public Command {
            if (expectedVersion < 0 || expectedVersion >= MAX_ENTRIES || commandId == null || actorId == null ||
                    permissionVersion < 1 || permissionVersion > MAX_PERMISSION_VERSION || traceId == null ||
                    !traceId.matches("[A-Za-z0-9._:-]{1,128}") || transition == null) throw invalid();
        }
    }
    public record Entry(byte[] bytes, String sha256) {
        public Entry {
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_ENTRY_BYTES ||
                    sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw invalid();
            bytes = bytes.clone(); if (!hash(bytes).equals(sha256)) throw invalid();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    /** A replay returns CURRENT state, never a stale UNKNOWN state that could be mistaken for permission to send. */
    public record Appended(DeliveryReceiptJournal journal, boolean replay, long appliedVersion) {}
    private record Decoded(long version, String previousSha256, Command command) {}
    private record Seen(Command command, long version) {}
    private final DeliveryReceiptSnapshotCodec.Document root;
    private final DeliveryReceiptState state;
    private final List<Entry> entries;
    private final Map<UUID,Seen> seen;
    private final String headSha256;

    private DeliveryReceiptJournal(DeliveryReceiptSnapshotCodec.Document root, DeliveryReceiptState state,
                                   List<Entry> entries, Map<UUID,Seen> seen, String headSha256) {
        this.root=root;this.state=state;this.entries=List.copyOf(entries);this.seen=Map.copyOf(seen);this.headSha256=headSha256;
    }
    public static DeliveryReceiptJournal queued(Scope scope, Instant at) {
        if (scope == null) throw invalid();
        var state=DeliveryReceiptState.queued(scope.tenantId(),scope.deliveryId(),scope.key(),at);
        var root=SNAPSHOTS.encode(state);
        return new DeliveryReceiptJournal(root,state,List.of(),Map.of(),root.sha256());
    }
    public Appended append(Command command) {
        if(command==null)throw invalid();
        var prior=seen.get(command.commandId());
        if(prior!=null){if(!prior.command().equals(command))throw conflict();return new Appended(this,true,prior.version());}
        if(entries.size()>=MAX_ENTRIES)throw conflict();
        var next=apply(state,command);
        // Exact receipt duplicates should be correlated to their original command, not create phantom versions.
        if(next.version()!=state.version()+1)throw conflict();
        var entry=encode(new Decoded(next.version(),headSha256,command));
        var history=new ArrayList<>(entries);history.add(entry);var commands=new HashMap<>(seen);commands.put(command.commandId(),new Seen(command,next.version()));
        return new Appended(new DeliveryReceiptJournal(root,next,history,commands,entry.sha256()),false,next.version());
    }
    public static DeliveryReceiptJournal restore(byte[] rootBytes,String rootSha256,Scope expected,
                                                 List<Entry> supplied,long expectedVersion,String expectedHeadSha256) {
        if(supplied==null||supplied.size()>MAX_ENTRIES||expectedVersion!=supplied.size()||expectedHeadSha256==null)throw invalid();
        try{
            var state=SNAPSHOTS.decode(rootBytes,rootSha256,expected);if(state.version()!=0)throw invalid();
            var root=SNAPSHOTS.encode(state);String head=root.sha256();var history=new ArrayList<Entry>();var commands=new HashMap<UUID,Seen>();
            for(var original:supplied){
                if(original==null)throw invalid();var entry=new Entry(original.bytes(),original.sha256());var decoded=decode(entry);
                if(decoded.version()!=state.version()+1||!decoded.previousSha256().equals(head)||decoded.command().expectedVersion()!=state.version()||commands.containsKey(decoded.command().commandId()))throw invalid();
                state=apply(state,decoded.command());if(state.version()!=decoded.version())throw invalid();
                commands.put(decoded.command().commandId(),new Seen(decoded.command(),state.version()));history.add(entry);head=entry.sha256();
            }
            if(!head.equals(expectedHeadSha256)||state.version()!=expectedVersion)throw invalid();
            return new DeliveryReceiptJournal(root,state,history,commands,head);
        }catch(DomainException|IllegalArgumentException rejected){throw invalid();}
    }
    private static DeliveryReceiptState apply(DeliveryReceiptState state,Command command){
        if(command.expectedVersion()!=state.version())throw new DomainException(409,"VERSION_CONFLICT","Delivery journal version changed");
        var transition=command.transition();
        if(transition instanceof Started started)return state.start(command.expectedVersion(),started.requestId(),started.observedAt());
        if(transition instanceof DeadLettered dead)return state.deadLetter(command.expectedVersion(),dead.observedAt());
        if(transition instanceof ReceiptRecorded recorded)return state.record(command.expectedVersion(),recorded.receipt(),recorded.observedAt());
        throw invalid();
    }
    private static Entry encode(Decoded decoded){
        try(var buffer=new ByteArrayOutputStream();var out=new DataOutputStream(buffer)){
            out.writeUTF(FORMAT);out.writeLong(decoded.version());out.write(HexFormat.of().parseHex(decoded.previousSha256()));var c=decoded.command();
            out.writeLong(c.expectedVersion());uuid(out,c.commandId());uuid(out,c.actorId());out.writeLong(c.permissionVersion());out.writeUTF(c.traceId());
            if(c.transition() instanceof Started started){out.writeByte(1);uuid(out,started.requestId());time(out,started.observedAt());}
            else if(c.transition() instanceof DeadLettered dead){out.writeByte(2);time(out,dead.observedAt());}
            else if(c.transition() instanceof ReceiptRecorded recorded){var r=recorded.receipt();out.writeByte(3);uuid(out,r.tenantId());uuid(out,r.deliveryId());uuid(out,r.requestId());uuid(out,r.receiptId());out.writeUTF(r.status().name());time(out,r.occurredAt());time(out,recorded.observedAt());}
            else throw invalid();
            out.flush();var bytes=buffer.toByteArray();return new Entry(bytes,hash(bytes));
        }catch(IOException|IllegalArgumentException rejected){throw invalid();}
    }
    private static Decoded decode(Entry entry){
        try(var in=new DataInputStream(new ByteArrayInputStream(entry.bytes()))){
            if(!FORMAT.equals(in.readUTF()))throw invalid();long version=in.readLong();byte[] previous=in.readNBytes(32);if(previous.length!=32)throw invalid();
            long expected=in.readLong();UUID command=uuid(in),actor=uuid(in);long permission=in.readLong();String trace=in.readUTF();Transition transition;
            switch(in.readUnsignedByte()){
                case 1 -> transition=new Started(uuid(in),time(in));
                case 2 -> transition=new DeadLettered(time(in));
                case 3 -> {UUID tenant=uuid(in),delivery=uuid(in),request=uuid(in),receipt=uuid(in);var status=ReceiptStatus.valueOf(in.readUTF());transition=new ReceiptRecorded(new Receipt(tenant,delivery,request,receipt,status,time(in)),time(in));}
                default -> throw invalid();
            }
            var decoded=new Decoded(version,HexFormat.of().formatHex(previous),new Command(expected,command,actor,permission,trace,transition));
            if(in.read()!=-1||!Arrays.equals(entry.bytes(),encode(decoded).bytes()))throw invalid();return decoded;
        }catch(IOException|IllegalArgumentException|DateTimeException rejected){throw invalid();}
    }
    public DeliveryReceiptState state(){return state;}
    public DeliveryReceiptSnapshotCodec.Document root(){return root;}
    public List<Entry> entries(){return entries;}
    public String headSha256(){return headSha256;}
    public Optional<Command> command(UUID id){var known=seen.get(id);return known==null?Optional.empty():Optional.of(known.command());}
    public OptionalLong commandVersion(UUID id){var known=seen.get(id);return known==null?OptionalLong.empty():OptionalLong.of(known.version());}
    public int encodedBytes(){return root.bytes().length+entries.stream().mapToInt(e->e.bytes().length).sum();}
    public boolean databasePersisted(){return false;}
    public boolean providerReceiptVerified(){return false;}
    public boolean automaticSendEnabled(){return false;}
    private static void uuid(DataOutputStream out,UUID value)throws IOException{out.writeLong(value.getMostSignificantBits());out.writeLong(value.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
    private static void time(DataOutputStream out,Instant value)throws IOException{out.writeLong(value.getEpochSecond());out.writeInt(value.getNano());}
    private static Instant time(DataInputStream in)throws IOException{long seconds=in.readLong();int nanos=in.readInt();if(nanos<0||nanos>999_999_999)throw invalid();return Instant.ofEpochSecond(seconds,nanos);}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}}
    private static DomainException invalid(){return new DomainException(503,"DELIVERY_JOURNAL_INVALID","通知回执日志范围、命令、顺序或完整性未通过核验；未恢复发送权限");}
    private static DomainException conflict(){return new DomainException(409,"DELIVERY_JOURNAL_CONFLICT","通知回执日志命令或状态冲突；未派发请求");}
}
