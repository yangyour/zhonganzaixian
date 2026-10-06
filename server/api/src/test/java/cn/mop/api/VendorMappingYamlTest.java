package cn.mop.api;

import cn.mop.adapters.monitoring.VendorMappingYaml;
import cn.mop.domain.monitoring.VendorMappingCandidate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class VendorMappingYamlTest {
    // Fictional file, not a real vendor/OID compatibility claim.
    private static final String YAML = """
        schema_version: '1'
        revision: 'synthetic-v2'
        rollback_revision: 'synthetic-v1'
        brand: '合成品牌'
        model: 'model-test'
        firmware: 'firmware-test'
        metrics:
          - id: temperature
            oid: '1.3.6.1.4.1.0.1'
            type: GAUGE
            source_unit: 'synthetic-tenths'
            target_unit: 'synthetic-degrees'
            scale: '0.1'
            offset: '-1'
            minimum: '-100'
            maximum: '100'
        """;
    static VendorMappingCandidate parse(String text) { return VendorMappingYaml.parse(text.getBytes(StandardCharsets.UTF_8)); }
    @Test void parsesUnicodeButNeverActivatesOrVerifiesSource() {
        var c = parse(YAML); assertEquals("合成品牌",c.brand()); assertFalse(c.effective()); assertFalse(c.sourceVerified());
        assertEquals(0,new BigDecimal("23.5").compareTo(c.metrics().get(0).convert("245")));
        assertEquals(parse(YAML).sourceSha256(),c.sourceSha256());
        assertNotEquals(parse(YAML+"# comment\n").sourceSha256(),c.sourceSha256());
    }
    @Test void immutableAndDefensive() {
        var c=parse(YAML); assertThrows(UnsupportedOperationException.class,()->c.metrics().clear());
        var list=new java.util.ArrayList<>(c.metrics());
        var copy=new VendorMappingCandidate(c.revision(),null,c.brand(),c.model(),c.firmware(),list,c.sourceSha256());
        list.clear();assertEquals(1,copy.metrics().size());
    }
    @ParameterizedTest @ValueSource(strings={"schema_version: '1'\n", "extra: true\n", "---\nfoo: bar\n", "...\ntrailing garbage\n"})
    void rejectsDuplicateUnknownAndMultipleDocuments(String suffix) { assertThrows(IllegalArgumentException.class,()->parse(YAML+suffix)); }
    @ParameterizedTest @ValueSource(strings={"!!java.net.URL 'https://example.invalid'", "!!str 'synthetic-v2'", "&version 'synthetic-v2'", "*version", "[a,b]", "2026-10-06", "null"})
    void rejectsTagsAnchorsAliasesAndWrongScalarTypes(String value) { assertThrows(IllegalArgumentException.class,()->parse(YAML.replace("'synthetic-v2'",value))); }
    @ParameterizedTest @ValueSource(strings={"3.1", "1.40", "0.40", "1.03.6", ".1.3", "1.3.", "1.3.4294967296", "1", "1.3.-1", "1.3.x"})
    void rejectsInvalidOid(String oid) { assertThrows(IllegalArgumentException.class,()->parse(YAML.replace("1.3.6.1.4.1.0.1",oid))); }
    @ParameterizedTest @ValueSource(strings={"NaN", "Infinity", "1e3", "+1", "01", " 1", "1 ", "0", "-1"})
    void rejectsInvalidScale(String scale) { assertThrows(IllegalArgumentException.class,()->parse(YAML.replace("scale: '0.1'","scale: '"+scale+"'"))); }
    @Test void rejectsUnquotedDecimalToAvoidBinaryFloatingPoint() { assertThrows(IllegalArgumentException.class,()->parse(YAML.replace("scale: '0.1'","scale: 0.1"))); }
    @Test void rejectsInvalidRangeAndRollback() {
        assertThrows(IllegalArgumentException.class,()->parse(YAML.replace("minimum: '-100'","minimum: '101'")));
        assertThrows(IllegalArgumentException.class,()->parse(YAML.replace("'synthetic-v1'","'synthetic-v2'")));
    }
    @Test void rejectsDuplicateMetricsAndEmptyList() {
        var metric=YAML.substring(YAML.indexOf("  - id:"));
        assertThrows(IllegalArgumentException.class,()->parse(YAML+metric));
        assertThrows(IllegalArgumentException.class,()->parse(YAML.substring(0,YAML.indexOf("metrics:"))+"metrics: []\n"));
    }
    @Test void byteUtf8DepthAndEventLimits() {
        assertThrows(IllegalArgumentException.class,()->VendorMappingYaml.parse(new byte[VendorMappingYaml.MAX_BYTES+1]));
        assertThrows(IllegalArgumentException.class,()->VendorMappingYaml.parse(new byte[]{(byte)0xc3,(byte)0x28}));
        assertThrows(IllegalArgumentException.class,()->parse("[".repeat(30)+"0"+"]".repeat(30)));
        assertThrows(IllegalArgumentException.class,()->parse("- a\n".repeat(21000)));
        assertThrows(IllegalArgumentException.class,()->VendorMappingYaml.parse(null));
    }
    @Test void failureDoesNotMutateAnExistingCandidateOrEchoDocument() {
        var previous=parse(YAML);var ex=assertThrows(IllegalArgumentException.class,()->parse("secret: DO_NOT_ECHO\n!!bad tag"));
        assertFalse(ex.toString().contains("DO_NOT_ECHO"));assertNull(ex.getCause());assertEquals("synthetic-v2",previous.revision());
    }
    @Test void exactConversionRejectsRangeOverflowAndFractionalCounters() {
        var m=parse(YAML).metrics().get(0);assertThrows(IllegalArgumentException.class,()->m.convert("2000"));
        var counter=new VendorMappingCandidate.Metric(m.id(),m.oid(),VendorMappingCandidate.ValueType.COUNTER,"count","count",BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("1000"));
        assertThrows(IllegalArgumentException.class,()->counter.convert("1.5"));assertThrows(IllegalArgumentException.class,()->counter.convert("-1"));assertEquals(0,BigDecimal.TEN.compareTo(counter.convert("10")));
        var large=new VendorMappingCandidate.Metric(m.id(),m.oid(),m.type(),"raw","scaled",new BigDecimal("9999999999999999999999999999999999999999"),BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("9999999999999999999999999999999999999999"));
        assertThrows(IllegalArgumentException.class,()->large.convert("2"));
    }
    @Test void metricCountAndOidArcLimits() {
        String prefix=YAML.substring(0,YAML.indexOf("  - id:")), metric=YAML.substring(YAML.indexOf("  - id:"));
        var many=new StringBuilder(prefix);
        for(int n=0;n<512;n++)many.append(metric.replace("id: temperature","id: metric_"+n));
        assertEquals(512,parse(many.toString()).metrics().size());
        assertThrows(IllegalArgumentException.class,()->parse(many+metric.replace("id: temperature","id: over_limit")));
        assertNotNull(parse(YAML.replace("1.3.6.1.4.1.0.1","2.4294967295")));
        assertThrows(IllegalArgumentException.class,()->parse(YAML.replace("1.3.6.1.4.1.0.1","1"+".3".repeat(128))));
    }
    @Test void rejectsInferredActivationAndUnknownMetricFields() {
        assertThrows(IllegalArgumentException.class,()->parse(YAML+"effective: true\n"));
        assertThrows(IllegalArgumentException.class,()->parse(YAML+"    password: DO_NOT_ECHO\n"));
        assertThrows(IllegalArgumentException.class,()->parse(YAML.replace("type: GAUGE","type: UNKNOWN")));
    }
    @Test void rejectsOversizedDecimalAndText() {
        assertThrows(IllegalArgumentException.class,()->parse(YAML.replace("scale: '0.1'","scale: '0."+"1".repeat(41)+"'")));
        assertThrows(IllegalArgumentException.class,()->parse(YAML.replace("model-test","m".repeat(129))));
    }
}
