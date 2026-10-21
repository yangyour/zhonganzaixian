package cn.mop.api;
import cn.mop.adapters.cmdb.CustomerCmdbDictionary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CustomerCmdbDictionaryTest {
 private final CustomerCmdbDictionary dictionary=CustomerCmdbDictionary.load(new ObjectMapper());
 @Test void retainsExactSourceTaxonomy(){assertEquals(9,dictionary.primaryTypes().size());assertEquals(16,dictionary.subtypes().size());assertEquals(4,dictionary.subtypesFor("FW").size());assertTrue(dictionary.subtype("fw_adc").isPresent());}
 @Test void validSubtypesMatchTheirParents(){for(var s:dictionary.subtypes()){var r=dictionary.classify(s.parent(),s.code(),true);assertTrue(r.classificationValid());assertFalse(r.assetCreated());assertFalse(r.collectionStarted());assertFalse(r.productionApproved());}}
 @Test void noFallbackForUnknownOrOther(){for(String code:new String[]{"other","switch","sw"," SW ",null})assertFalse(dictionary.classify(code,"sw_access",true).classificationValid());}
 @Test void mismatchedAndUnknownSubtypesFail(){assertFalse(dictionary.classify("SW","wl_ap",true).classificationValid());assertFalse(dictionary.classify("SW","unknown",true).classificationValid());}
 @Test void enteringServiceRequiresAnExplicitSubtype(){assertFalse(dictionary.classify("SW",null,true).classificationValid());assertTrue(dictionary.classify("SW",null,false).classificationValid());assertFalse(dictionary.classify("SW",null,false).monitoringMappingResolved());}
 @Test void adcRetainedButMonitoringMappingNotInvented(){var r=dictionary.classify("FW","fw_adc",true);assertTrue(r.classificationValid());assertFalse(r.monitoringMappingResolved());assertEquals("MONITORING_MAPPING_PENDING",r.issues().get(0).code());}
 @Test void collectionCodesAndExplicitDisplayLabelsOnly(){assertEquals("snmpv3",dictionary.collectionMethod("SNMPv3").orElseThrow());assertEquals("agent_active",dictionary.collectionMethod("Agent2 主动").orElseThrow());assertTrue(dictionary.collectionMethod("SSH").isEmpty());assertTrue(dictionary.collectionMethod(" snmpv3 ").isEmpty());}
 @Test void returnedCatalogCannotModifyLaterReads(){((ObjectNode)dictionary.document()).put("activated",true);assertFalse(dictionary.document().path("activated").asBoolean());assertThrows(UnsupportedOperationException.class,()->dictionary.primaryTypes().add("other"));assertThrows(UnsupportedOperationException.class,()->dictionary.subtypes().clear());}
 @Test void sourceIsTraceableAndNotPublished(){var doc=dictionary.document();assertEquals(95,doc.path("attributes").size());assertEquals(4,doc.path("unresolved").size());assertEquals("e603591f8621136da5662cf2f57f7ba518fc7328",doc.path("source").path("git_blob_sha").asText());assertFalse(doc.path("production_approved").asBoolean());}
}
