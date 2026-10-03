package cn.mop.persistence;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Pure exact JSON arithmetic equality, not a substitute component. */
class CapacitySnapshotJsonTest {
    final ObjectReader reader=new ObjectMapper().reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    @Test void numericRepresentationMayDifferButExactValueMustMatch()throws Exception{assertTrue(PostgresCapacityScenarioRepository.equalNumericJson(reader.readTree("{\"v\":1E+1}"),reader.readTree("{\"v\":10.000}")));assertFalse(PostgresCapacityScenarioRepository.equalNumericJson(reader.readTree("{\"v\":9007199254740993}"),reader.readTree("{\"v\":9007199254740992}")));}
    @Test void nestedArraysFieldsAndStringTypesCannotBeSubstituted()throws Exception{var base=reader.readTree("{\"v\":[null,1,{\"x\":\"1\"}]} ");for(String value:new String[]{"{\"v\":[null,1,{\"x\":1}]}","{\"v\":[1,null,{\"x\":\"1\"}]}","{\"v\":[null,1,{\"x\":\"1\"}],\"approved\":true}"})assertFalse(PostgresCapacityScenarioRepository.equalNumericJson(base,reader.readTree(value)));}
}
