package cn.mop.persistence;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MonitoringRegistrationProjectionTest {
 Map<String,Object> ticket(){return new LinkedHashMap<>(Map.of("source","customer_report","receipt_no","EVT-synthetic","reporter_id","64000000-0000-4000-8000-000000000001","created_at","2026-10-21T00:00:00Z"));}
 @Test void exactLegacyShapeUsesStoredFactsAndExplicitCompatibilityAction(){var input=Map.<String,Object>of("action","AUTO_CREATE_FROM_MONITORING_EVENT");var out=PostgresPlatformRepository.registrationHistoryProjection(input,1,ticket());assertEquals("LEGACY_MONITORING_REGISTER",out.get("action"));assertEquals(ticket().get("reporter_id"),out.get("actor_id"));assertEquals(ticket().get("created_at"),out.get("occurred_at"));assertEquals(1,input.size());assertEquals(4,out.size());}
 @Test void unrelatedOrExtendedMalformedHistoryIsNotSilentlyRepaired(){for(var input:List.of(Map.<String,Object>of("action","UNKNOWN"),Map.<String,Object>of("action","AUTO_CREATE_FROM_MONITORING_EVENT","unexpected",true))){assertEquals(input,PostgresPlatformRepository.registrationHistoryProjection(input,1,ticket()));}}
 @Test void customerReceiptAndLaterHistoryNeverUseCompatibilityProjection(){var input=Map.<String,Object>of("action","AUTO_CREATE_FROM_MONITORING_EVENT");var customer=ticket();customer.put("receipt_no","REG-synthetic");assertEquals(input,PostgresPlatformRepository.registrationHistoryProjection(input,1,customer));assertEquals(input,PostgresPlatformRepository.registrationHistoryProjection(input,2,ticket()));}
 @Test void onlyKnownThinPendingRegistrationErrorsReceiveReadCompatibility(){var thin=Map.<String,Object>of("code","CONFIG_PENDING");var result=PostgresPlatformRepository.registrationOperationErrorProjection(thin,"CUSTOMER_REPORT_REGISTRATION","PENDING","AWAITING_TICKET_CONFIGURATION");assertEquals(false,result.get("retryable"));assertEquals(List.of(),result.get("question_ids"));assertEquals(1,thin.size());assertEquals(thin,PostgresPlatformRepository.registrationOperationErrorProjection(thin,"FILE_UPLOAD","PENDING","STORING"));}
}
