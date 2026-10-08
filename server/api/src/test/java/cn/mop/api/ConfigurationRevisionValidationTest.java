package cn.mop.api;

import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Input-only checks: these tests do not assert remote integration. */
class ConfigurationRevisionValidationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode reference(String value) {
        return mapper.createObjectNode().put("reference", value).put("title", "虚构书面证据，尚未验证").put("sha256", "a".repeat(64));
    }

    @Test void writtenReferencesRejectPathsSecretsUnknownFieldsAndInvalidDigests() {
        for (String value : List.of("https://example.test/a", "C:\\evidence\\a.pdf", "document-ref:C:file", "document-ref:a/b", "document-ref:SECRET-note", "document-ref:access_token-1", "document-ref:password", "document-ref:private-key", "document-ref:", "document-ref:" + "a".repeat(148)))
            assertThrows(DomainException.class, () -> ConfigurationRevisionController.evidence(mapper.createArrayNode().add(reference(value))), value);
        assertThrows(DomainException.class, () -> ConfigurationRevisionController.evidence(mapper.createArrayNode().add(reference("document-ref:mop.note").put("sha256", "wrong"))));
        assertThrows(DomainException.class, () -> ConfigurationRevisionController.evidence(mapper.createArrayNode().add(reference("document-ref:mop.note").put("verified", true))));
        assertThrows(DomainException.class, () -> ConfigurationRevisionController.evidence(mapper.createArrayNode().add(reference("document-ref:mop.note").put("title", "证据\n"))));
        assertThrows(DomainException.class, () -> ConfigurationRevisionController.evidence(mapper.createArrayNode()));
        var tooMany = mapper.createArrayNode(); for (int i=0;i<6;i++) tooMany.add(reference("document-ref:mop."+i));
        assertThrows(DomainException.class, () -> ConfigurationRevisionController.evidence(tooMany));
    }

    @Test void referenceLengthAndUnicodeTextUseTheContractBoundaries() {
        String exact = "document-ref:" + "a".repeat(147);
        assertEquals(exact, ConfigurationRevisionController.evidence(mapper.createArrayNode().add(reference(exact))).get(0).get("reference"));
        String unicode = "😀".repeat(500);
        assertEquals(unicode, ConfigurationRevisionController.text(mapper.createObjectNode().put("reason", unicode), "reason", 500));
        assertThrows(DomainException.class, () -> ConfigurationRevisionController.text(mapper.createObjectNode().put("comment", unicode+"😀"), "comment", 500));
        for (String text : List.of("\n说明", "说明\t", "\u0000说明", " ", "\uD800"))
            assertThrows(DomainException.class, () -> ConfigurationRevisionController.text(mapper.createObjectNode().put("reason", text), "reason", 500));
    }

    @Test void proposalTypeAndNestedSecretChecksAreSharedWithDrafts() throws Exception {
        assertDoesNotThrow(() -> ConfigurationValueValidator.validate(Map.of("type", "integer"), mapper.readTree("30")));
        for (String body : List.of("null", "1.5", "\"30\"", "9223372036854775808"))
            assertThrows(DomainException.class, () -> ConfigurationValueValidator.validate(Map.of("type", "integer"), mapper.readTree(body)));
        for (String body : List.of("{\"nested\":[{\"password\":\"sample\"}]}", "{\"nested\":{\"ACCESS_TOKEN\":\"sample\"}}", "{\"nested\":{\"client_secret\":\"sample\"}}", "{\"nested\":{\"apiKey\":\"sample\"}}", "{\"nested\":{\"bearer-token\":\"sample\"}}", "{\"tenant_id\":\"sample\"}", "{\"note\":\"line\\nline\"}"))
            assertThrows(DomainException.class, () -> ConfigurationValueValidator.validate(Map.of("type", "object"), mapper.readTree(body)));
        assertDoesNotThrow(() -> ConfigurationValueValidator.validate(Map.of("type", "object"), mapper.readTree("{\"secret_ref\":\"document-ref:mop.credentials\",\"credential_ref\":\"document-ref:mop.reference\"}")));
        assertThrows(DomainException.class, () -> ConfigurationValueValidator.validate(Map.of("type", "string"), mapper.getNodeFactory().textNode("a".repeat(2001))));
    }
    @Test void nestedNumbersRejectOverflowInsteadOfPersistingQuotedInfinity() throws Exception {
        for (String body : List.of("{\"limit\":1e999}", "{\"limits\":[-1e999]}", "[1e999]")) {
            var value = mapper.readTree(body);
            assertThrows(DomainException.class, () -> ConfigurationValueValidator.validate(
                    Map.of("type", value.isArray() ? "array" : "object"), value), body);
        }
    }

    @Test void nestedFiniteNumbersAndUnknownNullRemainUnchanged() throws Exception {
        var value = mapper.readTree("{\"limit\":1.25,\"values\":[0,-3,1e100,null]}");
        var before = value.deepCopy();
        assertDoesNotThrow(() -> ConfigurationValueValidator.validate(Map.of("type", "object"), value));
        assertEquals(before, value);
        assertTrue(value.get("values").get(3).isNull());
    }

    @Test void runtimeCredentialNamesCannotEnterConfigurationProposals() {
        for(String key:List.of("OBJECT_STORAGE_ACCESS_KEY","OBJECT_STORAGE_SECRET_KEY","OBJECT_STORAGE_SECURITY_TOKEN","OBJECT_STORAGE_PHOTO_ACCESS_KEY","OBJECT_STORAGE_PHOTO_SECRET_KEY","OBJECT_STORAGE_IMPORT_ACCESS_KEY","OBJECT_STORAGE_IMPORT_SECRET_KEY","MOP_S3_ACCESS_KEY","MOP_S3_SECRET_KEY","MOP_IMPORT_S3_ACCESS_KEY","MOP_IMPORT_S3_SECRET_KEY","SPRING_DATASOURCE_PASSWORD","MOP_MIGRATION_PASSWORD","MOP_OIDC_CLIENT_SECRET","OSS_ACCESS_KEY_ID","OSS_ACCESS_KEY_SECRET","OSS_SECURITY_TOKEN")) {
            var value=mapper.createObjectNode();value.putObject("nested").put(key,"SYNTHETIC-NOT-A-CREDENTIAL");
            assertThrows(DomainException.class,()->ConfigurationValueValidator.validate(Map.of("type","object"),value),key);
        }
        assertDoesNotThrow(()->ConfigurationValueValidator.validate(Map.of("type","object"),mapper.createObjectNode().put("secret_ref","document-ref:synthetic-reference")));
    }

}
