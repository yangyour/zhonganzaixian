package cn.mop.importparser;

import cn.mop.domain.assetimport.source.ImportLimits;
import cn.mop.domain.assetimport.source.ParseFailure;
import java.util.function.LongSupplier;

/** Cooperative bound, backed by the caller's hard subprocess deadline. */
final class ParseBudget {
    private final LongSupplier clock;
    private final long started;
    private final long maxNanos;
    ParseBudget() { this(System::nanoTime, ImportLimits.MAX_PARSE_MILLIS); }
    ParseBudget(LongSupplier clock, long maxMillis) {
        this.clock=clock; this.started=clock.getAsLong(); this.maxNanos=maxMillis*1_000_000;
    }
    void check() {
        if(Thread.currentThread().isInterrupted() || clock.getAsLong()-started>=maxNanos)
            throw new ParseFailure(ParseFailure.Code.RESOURCE_LIMIT);
    }
}
