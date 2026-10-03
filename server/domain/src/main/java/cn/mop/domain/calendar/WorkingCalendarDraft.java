package cn.mop.domain.calendar;

import cn.mop.domain.DomainException;
import cn.mop.domain.time.IntervalSets;
import cn.mop.domain.time.IntervalSets.Interval;
import cn.mop.domain.time.WorkingTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Explicit draft windows only. Completeness is a maintainer assertion, never customer approval or publication. */
public record WorkingCalendarDraft(String name,Interval coverage,List<Interval> workingWindows,Boolean coverageComplete,String note) {
    public static final int MAX_WINDOWS=1000;
    private static final Instant MIN=Instant.parse("0001-01-01T00:00:00Z"),MAX=Instant.parse("9999-12-31T23:59:59.999Z");
    public WorkingCalendarDraft {
        text(name,100,false,false);text(note,2000,true,true);
        if(coverage==null||workingWindows==null||coverageComplete==null||workingWindows.size()>MAX_WINDOWS)throw invalid();
        instant(coverage.start());instant(coverage.end());
        for(Interval window:workingWindows){
            if(window==null)throw invalid();instant(window.start());instant(window.end());
            if(window.start().isBefore(coverage.start())||window.end().isAfter(coverage.end()))throw invalid();
        }
        // Retain the submitted order and overlaps in the draft; calculations use a separate union.
        workingWindows=List.copyOf(workingWindows);
    }
    public String timezone(){return "Asia/Shanghai";}
    public boolean effective(){return false;}
    public String customerConfirmation(){return "UNVERIFIED";}
    public List<Interval> normalizedWindows(){return IntervalSets.union(workingWindows);}
    public record Preview(WorkingTime.Result arithmetic) {
        public Preview {Objects.requireNonNull(arithmetic,"explicit arithmetic result");}
        public String scope(){return "UNPUBLISHED_DRAFT_SCENARIO";}
        public boolean effective(){return false;}
        public Instant scenarioDeadline(){return arithmetic.deadline();}
        public Instant effectiveDeadline(){return null;}
    }
    /** Identity/version must be supplied by the authorized caller; unknown time/duration stays unresolved. */
    public Preview preview(String draftId,long draftVersion,Instant start,Duration required){
        if(draftId==null||draftId.isBlank()||draftVersion<1||required!=null&&required.isNegative())throw invalid();
        if(start!=null)instant(start);
        return new Preview(WorkingTime.advance(new WorkingTime.CalendarData(draftId,draftVersion,coverage,workingWindows,coverageComplete),start,required));
    }
    private static void instant(Instant value){if(value==null||value.isBefore(MIN)||value.isAfter(MAX)||value.getNano()%1_000_000!=0)throw invalid();}
    private static void text(String value,int max,boolean nullable,boolean multiline){
        if(value==null){if(nullable)return;throw invalid();}
        if(value.isEmpty()||value.codePointCount(0,value.length())>max||value.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)||value.codePoints().anyMatch(cp->Character.getType(cp)==Character.SURROGATE||Character.isISOControl(cp)&&(!multiline||cp!='\n'&&cp!='\r'&&cp!='\t')))throw invalid();
    }
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","日历草稿需明确名称、毫秒精度覆盖范围、已知工作区间和覆盖声明；输入超出技术边界或含无效文字");}
}
