package cn.mop.persistence;
import cn.mop.domain.sla.TicketSlaDiagnosticLimits;
import cn.mop.domain.DomainException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TicketSlaDiagnosticLimitsTest {
 @Test void emptyDiagnosticsRemainValid(){assertDoesNotThrow(()->TicketSlaDiagnosticLimits.requireWithinLimits(0,0));}
 @Test void exactContractBoundaryIsAccepted(){assertDoesNotThrow(()->TicketSlaDiagnosticLimits.requireWithinLimits(64,20));}
 @Test void oneTaskBeyondContractIsRejectedWithoutTruncation(){var e=assertThrows(DomainException.class,()->TicketSlaDiagnosticLimits.requireWithinLimits(65,20));assertEquals(413,e.status);assertEquals("SLA_INSPECTION_LIMIT",e.code);}
 @Test void oneReferenceBeyondContractIsRejected(){assertEquals(413,assertThrows(DomainException.class,()->TicketSlaDiagnosticLimits.requireWithinLimits(64,21)).status);}
 @Test void impossibleNegativeCountsAreUnavailableNotEmpty(){assertEquals(503,assertThrows(DomainException.class,()->TicketSlaDiagnosticLimits.requireWithinLimits(-1,0)).status);assertEquals(503,assertThrows(DomainException.class,()->TicketSlaDiagnosticLimits.requireWithinLimits(0,-1)).status);}
 @Test void boundedSqlLookaheadIsExactlyOneExtraRow(){assertEquals(65,TicketSlaDiagnosticLimits.CLOCK_TASK_LOOKAHEAD);}
}
