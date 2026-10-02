package cn.mop.persistence;

import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure CLI policy tests. No network, database, migration, or alternate component is used. */
class TestSchemaMigrationTest {
    private static final List<String> SCRIPTS=List.of("V1__remote_test_platform.sql","V2__tenant_configuration_revisions.sql","V3__configuration_review_test_identity.sql","V4__durable_customer_report_registration.sql","V5__durable_report_photos.sql","V6__asset_import_sources_and_mapping.sql");
    private static List<TestSchemaMigration.Entry> entries(boolean photosApplied,boolean importsApplied) {
        List<TestSchemaMigration.Entry> entries=new ArrayList<>();for(int version=1;version<=6;version++) {
            boolean applied=version<5||version==5&&photosApplied||version==6&&importsApplied;entries.add(new TestSchemaMigration.Entry(Integer.toString(version),SCRIPTS.get(version-1),"SQL",applied?MigrationState.SUCCESS:MigrationState.PENDING,applied,true,123));
        }return entries;
    }
    @Test void defaultAndExplicitCheckNeverRequestApply(){assertFalse(TestSchemaMigration.applyRequested(new String[0]));assertFalse(TestSchemaMigration.applyRequested(new String[]{"--check"}));assertTrue(TestSchemaMigration.applyRequested(new String[]{"--apply-photos"}));assertTrue(TestSchemaMigration.applyRequested(new String[]{"--apply-asset-imports"}));assertEquals(TestSchemaMigration.Mode.APPLY_ASSET_IMPORTS,TestSchemaMigration.requestedMode(new String[]{"--apply-asset-imports"}));for(String[] invalid:List.of(new String[]{"--apply"},new String[]{"--check","--apply-photos"},new String[]{"--apply-photos","--apply-asset-imports"},new String[]{"repair"}))assertThrows(IllegalArgumentException.class,()->TestSchemaMigration.applyRequested(invalid));}
    @Test void dedicatedDestinationAndRoleCannotBeOverriddenWithConnectionOptionsOrCredentials(){
        Map<String,String> environment=new HashMap<>(Map.of("SPRING_DATASOURCE_URL","jdbc:postgresql://127.0.0.1:15432/mop_test","MOP_MIGRATION_USERNAME","mop_test_migrator","MOP_MIGRATION_PASSWORD","UNIT_ONLY_SECRET"));
        assertFalse(TestSchemaMigration.Settings.from(environment::get).toString().contains("UNIT_ONLY_SECRET"));
        for(String url:List.of("jdbc:postgresql://localhost:15432/mop_test","jdbc:postgresql://127.0.0.1:15432/other","jdbc:postgresql://127.0.0.1:15432/mop_test?password=UNIT_ONLY_SECRET","jdbc:postgresql://127.0.0.1:0/mop_test","jdbc:postgresql://127.0.0.1:65536/mop_test")){environment.put("SPRING_DATASOURCE_URL",url);assertThrows(IllegalArgumentException.class,()->TestSchemaMigration.Settings.from(environment::get));}
        environment.put("SPRING_DATASOURCE_URL","jdbc:postgresql://127.0.0.1:15432/mop_test");environment.put("MOP_MIGRATION_USERNAME","mop_test_runtime");assertThrows(IllegalArgumentException.class,()->TestSchemaMigration.Settings.from(environment::get));
    }
    @Test void baselineAndEachExplicitTargetAreSeparateAndAlreadyAppliedImportsExecuteNothing(){
        var photosPending=TestSchemaMigration.inspect(entries(false,false));var importsPending=TestSchemaMigration.inspect(entries(true,false));var importsApplied=TestSchemaMigration.inspect(entries(true,true));assertEquals(TestSchemaMigration.Plan.PHOTOS_PENDING,photosPending);assertEquals(TestSchemaMigration.Plan.ASSET_IMPORTS_PENDING,importsPending);assertEquals(TestSchemaMigration.Plan.ASSET_IMPORTS_APPLIED,importsApplied);
        assertTrue(TestSchemaMigration.shouldMigrate(TestSchemaMigration.Mode.APPLY_PHOTOS,photosPending));assertThrows(IllegalStateException.class,()->TestSchemaMigration.shouldMigrate(TestSchemaMigration.Mode.APPLY_ASSET_IMPORTS,photosPending));assertFalse(TestSchemaMigration.shouldMigrate(TestSchemaMigration.Mode.APPLY_PHOTOS,importsPending));assertTrue(TestSchemaMigration.shouldMigrate(TestSchemaMigration.Mode.APPLY_ASSET_IMPORTS,importsPending));
        for(var mode:TestSchemaMigration.Mode.values())assertFalse(TestSchemaMigration.shouldMigrate(mode,importsApplied));for(var plan:TestSchemaMigration.Plan.values())assertFalse(TestSchemaMigration.shouldMigrate(TestSchemaMigration.Mode.READ_ONLY_CHECK,plan));assertThrows(IllegalStateException.class,()->TestSchemaMigration.inspect(entries(false,true)));
    }
    @Test void knownUnappliedV6AbovePhotoTargetRemainsPendingAndFrozenClasspathResourceIsVerified()throws Exception{
        List<TestSchemaMigration.Entry> entries=entries(true,false);var sixth=entries.get(5);entries.set(5,new TestSchemaMigration.Entry(sixth.version(),sixth.script(),sixth.type(),MigrationState.ABOVE_TARGET,false,false,sixth.checksum()));assertEquals(TestSchemaMigration.Plan.ASSET_IMPORTS_PENDING,TestSchemaMigration.inspect(entries));TestSchemaMigration.verifyImportResource();assertEquals(64,TestSchemaMigration.IMPORT_SHA256.length());
    }
    @Test void unknownExtraDuplicateRepeatableOrMissingVersionFailsClosed(){
        List<TestSchemaMigration.Entry> valid=entries(false,false);
        for(TestSchemaMigration.Entry invalid:List.of(new TestSchemaMigration.Entry("7","V7__unknown.sql","SQL",MigrationState.ABOVE_TARGET,false,true,1),new TestSchemaMigration.Entry(null,"R__unknown.sql","SQL",MigrationState.PENDING,false,true,1),valid.get(0))){List<TestSchemaMigration.Entry> extra=new ArrayList<>(valid);extra.add(invalid);assertThrows(IllegalStateException.class,()->TestSchemaMigration.inspect(extra));}
        List<TestSchemaMigration.Entry> missing=new ArrayList<>(valid);missing.remove(2);assertThrows(IllegalStateException.class,()->TestSchemaMigration.inspect(missing));
        List<TestSchemaMigration.Entry> duplicate=new ArrayList<>(valid);duplicate.set(2,valid.get(0));assertThrows(IllegalStateException.class,()->TestSchemaMigration.inspect(duplicate));
    }
    @Test void changedChecksumFailedFutureBaselineOrPendingBaselineFailsClosed(){
        for(MigrationState state:List.of(MigrationState.FAILED,MigrationState.FUTURE_SUCCESS,MigrationState.BASELINE,MigrationState.OUT_OF_ORDER,MigrationState.PENDING,MigrationState.MISSING_SUCCESS)) {
            List<TestSchemaMigration.Entry> entries=entries(false,false);var original=entries.get(0);entries.set(0,new TestSchemaMigration.Entry(original.version(),original.script(),original.type(),state,true,true,1));assertThrows(IllegalStateException.class,()->TestSchemaMigration.inspect(entries));
        }
        for(int index=0;index<6;index++){List<TestSchemaMigration.Entry> entries=entries(true,true);var original=entries.get(index);entries.set(index,new TestSchemaMigration.Entry(original.version(),original.script(),original.type(),original.state(),true,false,1));assertThrows(IllegalStateException.class,()->TestSchemaMigration.inspect(entries));}
    }
    @Test void failureSummaryCannotEchoSqlConnectionOrPassword(){String summary=TestSchemaMigration.safeFailure("validate",new SQLException("password=UNIT_ONLY_SECRET jdbc:postgresql://secret.invalid/private; SELECT secret"));assertEquals("MOP_SCHEMA status=FAILED stage=validate exception_type=SQLException",summary);}
}
