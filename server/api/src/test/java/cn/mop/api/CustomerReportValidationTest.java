package cn.mop.api;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Pure input/capability checks; remote durability is verified in the PostgreSQL suite. */
class CustomerReportValidationTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode body(){return mapper.createObjectNode().put("source","customer_report").put("description","虚构客户报障").put("category","网络不通").put("impact_scope","company").put("contact_name","测试联系人").put("contact_phone","+86 (010) 1234-5678").put("urgency","urgent");}
    @Test void acceptedCustomerInputKeepsIdentityOutOfTheBodyAndNeverAssignsPriority(){
        for(String role:List.of("viewer","tadmin")){CustomerReport report=CustomerReportValidator.parse(body(),role);assertNull(report.assetId());assertNull(report.title());assertEquals("urgent",report.urgency());}
        for(String field:List.of("tenant_id","actor_id","reporter_id","priority","state","created_at","operation_id"))assertEquals(400,assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().put(field,"forged"),"viewer")).status);
        for(String role:List.of("msp","padmin"))assertEquals(403,assertThrows(DomainException.class,()->CustomerReportValidator.parse(body(),role)).status);
        for(String source:List.of("manual","incident"))assertEquals(403,assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().put("source",source),"viewer")).status);
        assertEquals("CONFIG_PENDING",assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().put("source","manual"),"msp")).code);
        assertEquals("CONFIG_PENDING",assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().put("source","incident").put("incident_id","55000000-0000-4000-8000-000000000001"),"padmin")).code);
        assertEquals(400,assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().put("incident_id","55000000-0000-4000-8000-000000000001"),"viewer")).status);
    }
    @Test void requiredEnumsPhonesUuidAndUnicodeLengthsAreStrict(){
        for(String field:List.of("source","description","category","impact_scope","contact_name","contact_phone","urgency")){ObjectNode missing=body();missing.remove(field);assertThrows(DomainException.class,()->CustomerReportValidator.parse(missing,"viewer"));}
        for(String phone:List.of("extension123","++()","123/456","\n123"))assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().put("contact_phone",phone),"viewer"));
        for(String id:List.of("1-1-1-1-1","https://example.test","", "55000000-0000-4000-8000-000000000001/path"))assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().put("asset_id",id),"viewer"));
        assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().putNull("asset_id"),"viewer"));
        assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().put("category","未知类别"),"viewer"));assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().put("urgency","P1"),"viewer"));
        assertEquals("😀".repeat(3000),CustomerReportValidator.parse(body().put("description","😀".repeat(3000)),"viewer").description());
        assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().put("description","😀".repeat(3001)),"viewer"));
        assertEquals("一行\n二行\t说明",CustomerReportValidator.parse(body().put("description","一行\n二行\t说明"),"viewer").description());
        assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().put("description","说明\u0000"),"viewer"));assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().put("contact_name","\uD800"),"viewer"));
        assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().put("title","a".repeat(201)),"viewer"));
    }
    @Test void attachmentInputIsValidatedBeforeTheUnimplementedFileServiceGate(){
        assertDoesNotThrow(()->CustomerReportValidator.parse(body().set("attachment_ids",mapper.createArrayNode()),"viewer"));
        String id="55000000-0000-4000-8000-000000000001";DomainException pending=assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().set("attachment_ids",mapper.createArrayNode().add(id)),"viewer"));assertEquals(422,pending.status);assertEquals("CONFIG_PENDING",pending.code);assertTrue(pending.details.toString().contains("Q28"));
        assertEquals(400,assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().set("attachment_ids",mapper.createArrayNode().add(id).add(id)),"viewer")).status);
        assertEquals(400,assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().set("attachment_ids",mapper.createArrayNode().add("not-a-uuid")),"viewer")).status);
        var tooMany=mapper.createArrayNode();for(int i=1;i<=4;i++)tooMany.add("55000000-0000-4000-8000-00000000000"+i);assertEquals(400,assertThrows(DomainException.class,()->CustomerReportValidator.parse(body().set("attachment_ids",tooMany),"viewer")).status);
    }
    @Test void sharedRoleCapabilitiesPreserveOriginalConfigAuditAndAssetScope(){
        for(String role:List.of("viewer","tadmin","msp","padmin"))assertTrue(Capabilities.forRole(role).contains("tickets:read"));
        for(String role:List.of("viewer","tadmin")){assertTrue(Capabilities.forRole(role).contains("tickets:report"));assertFalse(Capabilities.forRole(role).contains("configurations:write"));assertFalse(Capabilities.forRole(role).contains("audit:read"));}
        for(String role:List.of("msp","padmin"))assertFalse(Capabilities.forRole(role).contains("tickets:report"));
        assertTrue(Capabilities.forRole("padmin").containsAll(Set.of("configurations:read","configurations:write","audit:read")));assertFalse(Capabilities.forRole("viewer").contains("assets:write"));assertTrue(Capabilities.forRole("tadmin").contains("assets:write"));assertFalse(Capabilities.forRole("unknown").contains("tickets:read"));
    }
}
