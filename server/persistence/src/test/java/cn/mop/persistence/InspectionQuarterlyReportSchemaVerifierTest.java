package cn.mop.persistence;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;

/** Pure attestation tests; no SQL execution or database evidence. */
class InspectionQuarterlyReportSchemaVerifierTest {
    @Test void resourceAndGuardAreUnchanged()throws Exception {InspectionQuarterlyReportSchemaVerifier.resource();}
    @Test void allCheckNamesAndDigestsAreRequiredIncludingSameCountWeakening() {
        var expected=InspectionQuarterlyReportSchemaVerifier.CHECK_HASHES;
        assertEquals(15,expected.size());InspectionQuarterlyReportSchemaVerifier.requireCheckHashes(expected);
        for(String name:expected.keySet()) {
            var changed=new HashMap<>(expected);changed.put(name,"0".repeat(64));
            assertEquals(expected.size(),changed.size());
            assertThrows(IllegalStateException.class,()->InspectionQuarterlyReportSchemaVerifier.requireCheckHashes(changed),name);
            changed.clear();changed.putAll(expected);changed.put("renamed_"+name,changed.remove(name));
            assertEquals(expected.size(),changed.size());
            assertThrows(IllegalStateException.class,()->InspectionQuarterlyReportSchemaVerifier.requireCheckHashes(changed),name);
        }
        assertThrows(IllegalStateException.class,()->InspectionQuarterlyReportSchemaVerifier.requireCheckHashes(Map.of()));
    }
    @Test void sessionSettingsAreRestoredOnVerificationSuccess()throws Exception {verifySettings(false,false);}
    @Test void sessionSettingsAreRestoredOnDefinitionMismatch()throws Exception {verifySettings(true,false);}
    @Test void sessionRestorationIsAttemptedOnCatalogFailure()throws Exception {verifySettings(false,true);}
    private void verifySettings(boolean mismatch,boolean queryFailure)throws Exception {
        var entries=new ArrayList<>(InspectionQuarterlyReportSchemaVerifier.CHECK_HASHES.entrySet());
        int[] index={-1},statementCount={0},restoreCount={0};var restored=new HashMap<Integer,String>();
        ResultSet settings=stub(ResultSet.class,(p,m,a)->switch(m.getName()) {
            case "next" -> true;case "getString" -> (Integer)a[0]==1?"Asia/Shanghai":"SQL, DMY";case "close" -> null;
            default -> throw new AssertionError(m.getName());
        });
        ResultSet definitions=stub(ResultSet.class,(p,m,a)->switch(m.getName()) {
            case "next" -> ++index[0]<entries.size();
            case "getString" -> (Integer)a[0]==1?entries.get(index[0]).getKey():mismatch?"0".repeat(64):entries.get(index[0]).getValue();
            case "close" -> null;default -> throw new AssertionError(m.getName());
        });
        Statement query=stub(Statement.class,(p,m,a)->switch(m.getName()) {
            case "executeQuery" -> {
                if(statementCount[0]==1)yield settings;
                assertTrue(((String)a[0]).contains("set_config('TimeZone','UTC',true)"));
                assertTrue(((String)a[0]).contains("set_config('DateStyle','ISO, MDY',true)"));
                if(queryFailure)throw new SQLException("synthetic catalog failure");yield definitions;
            }
            case "close" -> null;default -> throw new AssertionError(m.getName());
        });
        PreparedStatement restore=stub(PreparedStatement.class,(p,m,a)->switch(m.getName()) {
            case "setString" -> {restored.put((Integer)a[0],(String)a[1]);yield null;}
            case "execute" -> {restoreCount[0]++;yield true;}
            case "close" -> null;default -> throw new AssertionError(m.getName());
        });
        Connection c=stub(Connection.class,(p,m,a)->switch(m.getName()) {
            case "createStatement" -> {statementCount[0]++;yield query;}
            case "prepareStatement" -> {
                assertEquals("SELECT set_config('TimeZone',?,true),set_config('DateStyle',?,true)",a[0]);yield restore;
            }
            default -> throw new AssertionError("Verifier must not control caller transaction: "+m.getName());
        });
        if(queryFailure)assertThrows(SQLException.class,()->InspectionQuarterlyReportSchemaVerifier.verifyCheckDefinitions(c));
        else if(mismatch)assertThrows(IllegalStateException.class,()->InspectionQuarterlyReportSchemaVerifier.verifyCheckDefinitions(c));
        else InspectionQuarterlyReportSchemaVerifier.verifyCheckDefinitions(c);
        assertEquals(Map.of(1,"Asia/Shanghai",2,"SQL, DMY"),restored);assertEquals(1,restoreCount[0]);
    }
    private static <T>T stub(Class<T> type,InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }
}
