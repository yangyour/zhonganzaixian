package cn.mop.adapters.backup;

import cn.mop.domain.DomainException;
import com.github.difflib.DiffUtils;
import com.github.difflib.algorithm.myers.MyersDiffWithLinearSpace;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Bounded in-memory comparison; returns positions/counts only, never backup text. No storage or device access. */
public final class RedactedBackupDiff {
    public static final int MAX_BYTES=131072,MAX_LINES=1000,MAX_LINE_BYTES=8192,MAX_COMPARISONS=250000;
    public static final long MAX_NANOS=2_000_000_000L;
    public enum Status { IDENTICAL, CHANGED, LIMIT_EXCEEDED, CANCELLED, UNAVAILABLE }
    /** Intentionally not a record: auto-generated toString must never print configuration content. */
    public static final class VersionInput implements AutoCloseable {
        private final UUID tenantId,assetId,versionId;
        private final byte[] bytes;
        private boolean closed;
        public VersionInput(UUID tenantId,UUID assetId,UUID versionId,byte[] bytes){
            if(tenantId==null||assetId==null||versionId==null||bytes==null||bytes.length>MAX_BYTES)throw invalid();
            this.tenantId=tenantId;this.assetId=assetId;this.versionId=versionId;this.bytes=bytes.clone();
        }
        @Override
        public void close() {
            Arrays.fill(bytes, (byte) 0);
            closed = true;
        }
    }
    public record Hunk(int beforeStart,int beforeCount,int afterStart,int afterCount){
        public Hunk{if(beforeStart<0||afterStart<0||beforeCount<0||afterCount<0||beforeStart>MAX_LINES||afterStart>MAX_LINES||beforeCount>MAX_LINES||afterCount>MAX_LINES||beforeCount+afterCount==0||beforeStart+beforeCount>MAX_LINES||afterStart+afterCount>MAX_LINES)throw invalid();}
    }
    public record Summary(Status status,Integer beforeLines,Integer afterLines,List<Hunk> hunks){
        public Summary {
            if(status==null||hunks==null||hunks.size()>2*MAX_LINES)throw invalid();hunks=List.copyOf(hunks);
            boolean compared=status==Status.IDENTICAL||status==Status.CHANGED;
            if(compared){
                if(beforeLines==null||afterLines==null||beforeLines<0||afterLines<0||beforeLines>MAX_LINES||afterLines>MAX_LINES||(status==Status.IDENTICAL)!=hunks.isEmpty())throw invalid();
                int removed=0,added=0;for(Hunk h:hunks){if(h.beforeStart()+h.beforeCount()>beforeLines||h.afterStart()+h.afterCount()>afterLines)throw invalid();removed+=h.beforeCount();added+=h.afterCount();}
                if(removed>beforeLines||added>afterLines||beforeLines-removed+added!=afterLines)throw invalid();
            }else if(beforeLines!=null||afterLines!=null||!hunks.isEmpty())throw invalid();
        }
        public Boolean changed(){return switch(status){case IDENTICAL->false;case CHANGED->true;default->null;};}
        public String redaction(){return "ALL_TEXT_WITHHELD";}
        public String noisePolicy(){return "NONE_APPLIED_UNCONFIRMED";}
        public boolean normalized(){return false;}
        public boolean changeApproved(){return false;}
        public boolean automaticTicketAllowed(){return false;}
    }
    private final LongSupplier clock;
    private final long maxNanos;
    private final int maxComparisons;
    public RedactedBackupDiff(){this(System::nanoTime,MAX_NANOS,MAX_COMPARISONS);}
    RedactedBackupDiff(LongSupplier clock,long maxNanos,int maxComparisons){this.clock=Objects.requireNonNull(clock);if(maxNanos<1||maxComparisons<1)throw invalid();this.maxNanos=maxNanos;this.maxComparisons=maxComparisons;}
    /** Must be called only after current authorization of both versions; these checks do not replace that authorization. */
    public Summary compare(UUID tenantId,UUID assetId,VersionInput before,VersionInput after){
        if(tenantId==null||assetId==null||before==null||after==null)throw invalid();
        for (VersionInput input : List.of(before, after)) {
            if (input.closed) {
                throw invalid();
            }
            if (!tenantId.equals(input.tenantId) || !assetId.equals(input.assetId)) {
                throw new DomainException(404, "NOT_FOUND", "备份版本不存在");
            }
        }
        if(before.versionId.equals(after.versionId)&&!Arrays.equals(before.bytes,after.bytes))throw new DomainException(409,"BACKUP_VERSION_CONFLICT","同一不可变版本内容不一致");
        Budget budget=new Budget(clock.getAsLong());
        try{
            budget.check();List<String> left=lines(before.bytes,budget),right=lines(after.bytes,budget);
            var patch=DiffUtils.diff(left,right,new MyersDiffWithLinearSpace<String>((a,b)->{budget.comparison();return a.equals(b);}));budget.check();
            var hunks=patch.getDeltas().stream().map(d->new Hunk(d.getSource().getPosition(),d.getSource().size(),d.getTarget().getPosition(),d.getTarget().size())).toList();
            return new Summary(hunks.isEmpty()?Status.IDENTICAL:Status.CHANGED,left.size(),right.size(),hunks);
        }catch(Bounded failure){return new Summary(failure.status,null,null,List.of());
        }catch(DomainException invalid){throw invalid;
        }catch(Exception failure){return new Summary(Status.UNAVAILABLE,null,null,List.of());}
    }
    private List<String> lines(byte[] bytes,Budget budget){
        final String value;
        try{value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException failure){throw invalid();}
        if(value.indexOf('\0')>=0)throw invalid();
        List<String> lines=new ArrayList<>();int start=0;
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);if(c=='\r'||c=='\n'){
                int end=i+1;if(c=='\r'&&end<value.length()&&value.charAt(end)=='\n'){end++;i++;}
                add(lines,value.substring(start,end),budget);start=end;
            }
        }
        if(start<value.length())add(lines,value.substring(start),budget);
        return lines;
    }
    private void add(List<String> lines,String line,Budget budget){budget.check();if(lines.size()==MAX_LINES||line.getBytes(StandardCharsets.UTF_8).length>MAX_LINE_BYTES)throw new Bounded(Status.LIMIT_EXCEEDED);lines.add(line);}
    private final class Budget {
        private final long start;private int comparisons;
        Budget(long start){this.start=start;}
        void check(){if(Thread.currentThread().isInterrupted())throw new Bounded(Status.CANCELLED);if(clock.getAsLong()-start>=maxNanos)throw new Bounded(Status.LIMIT_EXCEEDED);}
        void comparison(){check();if(++comparisons>maxComparisons)throw new Bounded(Status.LIMIT_EXCEEDED);}
    }
    private static final class Bounded extends RuntimeException{final Status status;Bounded(Status status){super("Backup comparison budget reached");this.status=status;}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","备份差异输入或结果结构无效，未输出配置正文");}
}
