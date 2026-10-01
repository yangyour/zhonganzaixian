package cn.mop.persistence;

import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure CLI policy tests. No network, database, migration, or alternate component is used. */
class TestSchemaMigrationTest {
    private static final List<String> SCRIPTS=List.of("V1__remote_test_platform.sql","V2__tenant_configuration_revisions.sql","V3__configuration_review_test_identity.sql","V4__durable_customer_report_registration.sql","V5__durable_report_photos.sql");
    private static List<TestSchemaMigration.Entry> entries(boolean photosApplied) {
        List<TestSchemaMigration.Entry> entries=new ArrayList<>();for(int version=1;version<=5;version++) {
            boolean applied=version<5||photosApplied;entries.add(new TestSchemaMigration.Entry(Integer.toString(version),SCRIPTS.get(version-1),"SQL",applied?MigrationState.SUCCESS:MigrationState.PENDING,applied,true,123));
        }return entries;
    }
    @Test void defaultAndExplicitCheckNeverRequestApply(){assertFalse(TestSchemaMigration.applyRequested(new String[0]));assertFalse(TestSchemaMigration.applyRequested(new String[]{"--check"}));assertTrue(TestSchemaMigration.applyRequested(new String[]{"--apply-photos"}));for(String[] invalid:List.of(new String[]{"--apply"},new String[]{"--check","--apply-photos"},new String[]{"repair"}))assertThrows(IllegalArgumentException.class,()->TestSchemaMigration.applyRequested(invalid));}
    @Test void dedicatedDestinationAndRoleCannotBeOverriddenWithConnectionOptionsOrCredentials(){
        Map<String,String> environment=new HashMap<>(Map.of("SPRING_DATASOURCE_URL","jdbc:postgresql://127.0.0.1:15432/mop_test","MOP_MIGRATION_USERNAME","mop_test_migrator","MOP_MIGRATION_PASSWORD","UNIT_ONLY_SECRET"));
        assertFalse(TestSchemaMigration.Settings.from(environment::get).toString().contains("UNIT_ONLY_SECRET"));
        for(String url:List.of("jdbc:postgresql://localhost:15432/mop_test","jdbc:postgresql://127.0.0.1:15432/other","jdbc:postgresql://127.0.0.1:15432/mop_test?password=UNIT_ONLY_SECRET","jdbc:postgresql://127.0.0.1:0/mop_test","jdbc:postgresql://127.0.0.1:65536/mop_test")){environment.put("SPRING_DATASOURCE_URL",url);assertThrows(IllegalArgumentException.class,()->TestSchemaMigration.Settings.from(environment::get));}
        environment.put("SPRING_DATASOURCE_URL","jdbc:postgresql://127.0.0.1:15432/mop_test");environment.put("MOP_MIGRATION_USERNAME","mop_test_runtime");assertThrows(IllegalArgumentException.class,()->TestSchemaMigration.Settings.from(environment::get));
    }
    @Test void onlyAppliedBaselineWithExactlyOnePendingPhotoVersionCanApply(){assertEquals(TestSchemaMigration.Plan.PHOTOS_PENDING,TestSchemaMigration.inspect(entries(false)));assertEquals(TestSchemaMigration.Plan.PHOTOS_APPLIED,TestSchemaMigration.inspect(entries(true)));}
    @Test void unknownExtraDuplicateRepeatableOrMissingVersionFailsClosed(){
        List<TestSchemaMigration.Entry> valid=entries(false);
        for(TestSchemaMigration.Entry invalid:List.of(new TestSchemaMigration.Entry("6","V6__unknown.sql","SQL",MigrationState.ABOVE_TARGET,false,true,1),new TestSchemaMigration.Entry(null,"R__unknown.sql","SQL",MigrationState.PENDING,false,true,1),valid.get(0))){List<TestSchemaMigration.Entry> extra=new ArrayList<>(valid);extra.add(invalid);assertThrows(IllegalStateException.class,()->TestSchemaMigration.inspect(extra));}
        List<TestSchemaMigration.Entry> missing=new ArrayList<>(valid);missing.remove(2);assertThrows(IllegalStateException.class,()->TestSchemaMigration.inspect(missing));
        List<TestSchemaMigration.Entry> duplicate=new ArrayList<>(valid);duplicate.set(2,valid.get(0));assertThrows(IllegalStateException.class,()->TestSchemaMigration.inspect(duplicate));
    }
    @Test void changedChecksumFailedFutureBaselineOrPendingBaselineFailsClosed(){
        for(MigrationState state:List.of(MigrationState.FAILED,MigrationState.FUTURE_SUCCESS,MigrationState.BASELINE,MigrationState.OUT_OF_ORDER,MigrationState.PENDING,MigrationState.MISSING_SUCCESS)) {
            List<TestSchemaMigration.Entry> entries=entries(false);var original=entries.get(0);entries.set(0,new TestSchemaMigration.Entry(original.version(),original.script(),original.type(),state,true,true,1));assertThrows(IllegalStateException.class,()->TestSchemaMigration.inspect(entries));
        }
        List<TestSchemaMigration.Entry> entries=entries(true);var original=entries.get(4);entries.set(4,new TestSchemaMigration.Entry(original.version(),original.script(),original.type(),original.state(),true,false,1));assertThrows(IllegalStateException.class,()->TestSchemaMigration.inspect(entries));
    }
    @Test void failureSummaryCannotEchoSqlConnectionOrPassword(){String summary=TestSchemaMigration.safeFailure("validate",new SQLException("password=UNIT_ONLY_SECRET jdbc:postgresql://secret.invalid/private; SELECT secret"));assertEquals("MOP_SCHEMA status=FAILED stage=validate exception_type=SQLException",summary);}
}
