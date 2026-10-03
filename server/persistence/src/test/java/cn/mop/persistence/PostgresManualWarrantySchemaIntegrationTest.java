package cn.mop.persistence;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.mop.persistence.PostgresAssetMetadataSchemaIntegrationTest.*;

/** Explicit V15 prerequisite; real runtime role and rollback-only synthetic transactions. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresManualWarrantySchemaIntegrationTest {
    @BeforeAll static void ready()throws SQLException{
        prepare();
        try(var c=connect();var s=c.createStatement();var r=s.executeQuery("SELECT count(*)=3 FROM information_schema.columns WHERE table_schema='public' AND table_name='mop_asset_manual_revision' AND column_name IN ('warranty_start','warranty_supplier','warranty_details_declared')")){assertTrue(r.next());assertTrue(r.getBoolean(1));}
    }
    static void add(Connection c,String id,long version,Long previous,String who,Boolean declared,LocalDate start,LocalDate expiry,String supplier)throws SQLException{
        try(var s=c.prepareStatement("INSERT INTO public.mop_asset_manual_revision(tenant_id,asset_id,asset_version,previous_metadata_version,actor_id,name,type,management_ip,site_id,note,before_snapshot,warranty_details_declared,warranty_start,warranty_expiry,warranty_supplier) VALUES (?::uuid,?::uuid,?,?,?::uuid,'synthetic warranty','router','192.0.2.77','site-a-main','synthetic warranty note',?::jsonb,?,?,?,?)")){
            s.setString(1,TENANT);s.setString(2,id);s.setLong(3,version);s.setObject(4,previous,Types.BIGINT);s.setString(5,who);s.setString(6,before(id,version-1,previous!=null).toString());s.setObject(7,declared,Types.BOOLEAN);s.setObject(8,start);s.setObject(9,expiry);s.setString(10,supplier);s.executeUpdate();
        }
    }
    static void state(Connection c,String id,long version,boolean declared,LocalDate start,String supplier)throws SQLException{
        try(var s=c.prepareStatement("SELECT warranty_details_declared,warranty_start,warranty_supplier,verification_status,monitoring_applied,cmdb_applied FROM public.mop_asset_manual_revision WHERE asset_id=?::uuid AND asset_version=?")){
            s.setString(1,id);s.setLong(2,version);try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(declared,r.getBoolean(1));assertEquals(start,r.getObject(2,LocalDate.class));assertEquals(supplier,r.getString(3));assertEquals("SELF_REPORTED",r.getString(4));assertFalse(r.getBoolean(5));assertFalse(r.getBoolean(6));assertFalse(r.next());}
        }
    }
    @Test void legacyOmissionRemainsUndeclaredAndNullable()throws SQLException{
        try(var c=connect()){String id=asset(c);valid(c,id);state(c,id,2,false,null,null);
            revision(c,id,3,2L,actor,null,"site-a-main",before(id,2,true),"legacy update","SELF_REPORTED",false,null);state(c,id,3,false,null,null);c.rollback();}
    }
    @Test void explicitUnknownIsDistinctFromNoDeclarationAndDoesNotInventCoverage()throws SQLException{
        try(var c=connect()){String id=asset(c);add(c,id,2,null,actor,true,null,null,null);state(c,id,2,true,null,null);c.rollback();}
    }
    @Test void knownBoundsUnicodeSupplierAndRawSpacesRoundTrip()throws SQLException{
        for(String supplier:List.of("  合同供应商  ","😀".repeat(200)))try(var c=connect()){
            String id=asset(c);add(c,id,2,null,actor,true,LocalDate.of(1,1,1),LocalDate.of(9999,12,31),supplier);state(c,id,2,true,LocalDate.of(1,1,1),supplier);c.rollback();
        }
    }
    @Test void declaredFlagIsRequiredForAnySupplementalFact()throws SQLException{
        for(boolean useDate:List.of(true,false))try(var c=connect()){
            String id=asset(c);assertEquals("23514",assertThrows(SQLException.class,()->add(c,id,2,null,actor,false,useDate?LocalDate.of(2026,1,1):null,null,useDate?null:"supplier")).getSQLState());c.rollback();
        }
        try(var c=connect()){String id=asset(c);assertEquals("23502",assertThrows(SQLException.class,()->add(c,id,2,null,actor,null,null,null,null)).getSQLState());c.rollback();}
    }
    @Test void invalidSupplierTextIsRejectedWithoutChangingHistory()throws SQLException{
        for(String supplier:List.of(""," ","\u00a0\u3000\ufeff","\t","x\ny","x\u0085y"))try(var c=connect()){
            String id=asset(c);assertEquals("23514",assertThrows(SQLException.class,()->add(c,id,2,null,actor,true,null,null,supplier)).getSQLState());c.rollback();
        }
        try(var c=connect()){String id=asset(c);assertEquals("22001",assertThrows(SQLException.class,()->add(c,id,2,null,actor,true,null,null,"😀".repeat(201))).getSQLState());c.rollback();}
    }
    @Test void datesHaveBoundedRangeAndKnownOrderButUnknownRemainsAllowed()throws SQLException{
        for(LocalDate start:List.of(LocalDate.of(0,1,1),LocalDate.of(10000,1,1)))try(var c=connect()){
            String id=asset(c);assertEquals("23514",assertThrows(SQLException.class,()->add(c,id,2,null,actor,true,start,null,null)).getSQLState());c.rollback();
        }
        try(var c=connect()){String id=asset(c);assertEquals("23514",assertThrows(SQLException.class,()->add(c,id,2,null,actor,true,LocalDate.of(2026,2,1),LocalDate.of(2026,1,1),null)).getSQLState());c.rollback();}
        try(var c=connect()){String id=asset(c);var date=LocalDate.of(9999,12,31);add(c,id,2,null,actor,true,date,date,null);add(c,id,3,2L,actor,true,date,null,null);c.rollback();}
    }
    @Test void oldWriterCannotForgetPreviouslyDeclaredFacts()throws SQLException{
        try(var c=connect()){String id=asset(c);add(c,id,2,null,actor,true,LocalDate.of(2026,1,1),null,"supplier");var save=c.setSavepoint();
            assertEquals("23514",assertThrows(SQLException.class,()->revision(c,id,3,2L,actor,null,"site-a-main",before(id,2,true),"legacy overwrite","SELF_REPORTED",false,null)).getSQLState());c.rollback(save);
            state(c,id,2,true,LocalDate.of(2026,1,1),"supplier");c.rollback();}
    }
    @Test void explicitClearPreservesHistoricalFactsAndStillBlocksOldDefaults()throws SQLException{
        try(var c=connect()){String id=asset(c);add(c,id,2,null,actor,true,null,null,"supplier");add(c,id,3,2L,actor,true,null,null,null);state(c,id,2,true,null,"supplier");state(c,id,3,true,null,null);var save=c.setSavepoint();
            assertEquals("23514",assertThrows(SQLException.class,()->revision(c,id,4,3L,actor,null,"site-a-main",before(id,3,true),"legacy after clear","SELF_REPORTED",false,null)).getSQLState());c.rollback(save);state(c,id,3,true,null,null);c.rollback();}
    }
    @Test void foreignCompositeReferencesRemainRejected()throws SQLException{
        try(var c=connect()){String id=asset(c);assertEquals("23503",assertThrows(SQLException.class,()->add(c,id,2,null,foreignActor,true,null,null,"supplier")).getSQLState());c.rollback();}
        try(var c=connect()){String id=UUID.randomUUID().toString();assertEquals("23503",assertThrows(SQLException.class,()->add(c,id,2,null,actor,true,null,null,null)).getSQLState());c.rollback();}
    }
    @Test void runtimeCannotMutateExistingFactsAndGuardHasRestrictedInvokerRights()throws SQLException{
        try(var c=connect();var s=c.createStatement();var r=s.executeQuery("SELECT has_table_privilege(current_user,'public.mop_asset_manual_revision','UPDATE,DELETE,TRUNCATE'),has_column_privilege(current_user,'public.mop_asset_manual_revision','warranty_start','UPDATE'),has_column_privilege(current_user,'public.mop_asset_manual_revision','warranty_supplier','UPDATE'),has_column_privilege(current_user,'public.mop_asset_manual_revision','warranty_details_declared','UPDATE')")){assertTrue(r.next());for(int i=1;i<=4;i++)assertFalse(r.getBoolean(i));}
        try(var c=connect();var s=c.createStatement();var r=s.executeQuery("SELECT NOT prosecdef,proconfig=ARRAY['search_path=pg_catalog'],encode(sha256(convert_to(prosrc,'UTF8')),'hex'),has_function_privilege(current_user,oid,'EXECUTE'),NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(proacl,acldefault('f',proowner))) a WHERE a.grantee=0) FROM pg_proc WHERE oid='public.mop_warranty_declaration_guard()'::regprocedure")){assertTrue(r.next());assertTrue(r.getBoolean(1));assertTrue(r.getBoolean(2));assertEquals(TestSchemaMigration.WARRANTY_GUARD_SHA256,r.getString(3));assertTrue(r.getBoolean(4));assertTrue(r.getBoolean(5));}
        for(String action:List.of("UPDATE public.mop_asset_manual_revision SET warranty_supplier='tamper'","DELETE FROM public.mop_asset_manual_revision","TRUNCATE public.mop_asset_manual_revision"))try(var c=connect();var s=c.createStatement()){assertEquals("42501",assertThrows(SQLException.class,()->s.executeUpdate(action)).getSQLState());c.rollback();}
    }
}
