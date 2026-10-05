package cn.mop.domain.monitoring;

import cn.mop.domain.DomainException;
import java.math.BigDecimal;
import java.util.List;

/** M4-3 display colors only. Never changes alert thresholds, checks health or derives rates from counters. */
public final class PortUtilizationDisplay {
    public enum Highlight { UNKNOWN, NO_HIGHLIGHT, YELLOW, RED }
    public enum Column { PORT, STATE, SPEED_DUPLEX, BANDWIDTH_UTILIZATION, IN_OUT_TRAFFIC, ERRORS_DISCARDS, VLAN, DESCRIPTION_PEER }
    private PortUtilizationDisplay(){}
    public static List<Column> columns(){return List.of(Column.values());}
    public static Highlight highlight(String explicitPercent){
        if(explicitPercent==null)return Highlight.UNKNOWN;
        if(explicitPercent.length()>81||!explicitPercent.matches("(?:0|[1-9][0-9]{0,39})(?:\\.[0-9]{1,40})?"))throw new DomainException(400,"VALIDATION_ERROR","端口展示利用率须为明确非负十进制百分数；不解析计数器或猜测分母");
        var value=new BigDecimal(explicitPercent);
        if(value.compareTo(new BigDecimal("80"))>0)return Highlight.RED;
        if(value.compareTo(new BigDecimal("50"))>0)return Highlight.YELLOW;
        return Highlight.NO_HIGHLIGHT;
    }
}
